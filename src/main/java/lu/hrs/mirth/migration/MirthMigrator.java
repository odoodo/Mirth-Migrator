package lu.hrs.mirth.migration;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.XML;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.json.JsonParser;
import org.mozilla.javascript.json.JsonParser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides the API that allows the Mirth Migrator to communicate w/ the configured Mirth instances
 *
 * License: MPL 2.0
 * Project home: https://github.com/odoodo/Mirth-Migrator
 *
 * @author ortwin.donak
 *
 */
public class MirthMigrator {

	private final static String version = "1.0";

	/** The identifier or the component type channel */
	public final static String CHANNEL = "channel";
	/** The identifier or the component type code template */
	public final static String CODE_TEMPLATE = "codeTemplate";
	/** The identifier or the component type channel group */
	public final static String CHANNEL_GROUP = "channelGroup";
	/** The identifier or the component type channel tags */
	public final static String CHANNEL_TAG = "channelTag";
	/** The identifier or the component type inter-channel dependencies */
	public final static String INTER_CHANNEL_DEPENDENCY = "interChannelDependency";
	/** The identifier or the component type channel pruning */
	public final static String CHANNEL_PRUNING = "channelPruning";
	/** The identifier or the component type code template library */
	public final static String CODE_TEMPLATE_LIBRARY = "codeTemplateLibrary";
	/**
	 * The component type could not be determined (usually because the component definition did not contain any indicator that would allow to
	 * determine the type)
	 */
	public final static String UNKNOWN = "unknown";
	/** Key for decrypting user credentials */
	private final static String CREDENTIALS_KEY = "}G~8.I$+dC4ObH2qG\\VM4088<115Hyf]W=7Nf`6bi@%'^4_uO4";

	private final static DateFormat displayDate = new SimpleDateFormat("dd.MM.yyyy, HH:mm:ss");
	private final static String clientIdentifier = "MirthMigrator";

	/** used for parsing the change date in the channel description */
	private final static SimpleDateFormat changeParseDateFormat = new SimpleDateFormat("yyyyMMdd");
	/** used for formatting the change date in the channel details table */
	private final static SimpleDateFormat changeDisplayDateFormat = new SimpleDateFormat("dd.MM.yyyy");

	private MirthVersion mirthVersion = null;

	private String systemName, environment, description, server, username, password, hash;
	private int port;

	// indicates when the client was last updated (this is needed for automated refresh)
	private Long lastUpdate = null;

	/**
	 * A cash for the Mirth client instances used to access the different Mirth systems. Those are shared by all sessions
	 */
	private static HashMap<String, MirthMigrator> mirthClients = null;

	/**
	 * The location at which the Mirth Migrator configuration file can be found
	 */
	private final static String configurationFileLocation = ".\\web\\MirthMigrator\\config\\MirthMigration.conf";

	/**
	 * The configured Mirth environments
	 */
	private static HashMap<String, HashMap<String, String>> mirthEnvironments = null;

	/**
	 * The Mirth Migrator configuration
	 */
	private static JSONObject configuration = null;

	/**
	 * A list of functions that should not be recognized as custom functions
	 */
	private static ArrayList<String> functionFilter = new ArrayList<String>();

	/**
	 * The session cookie of the current session.
	 */
	private String serverSessionCookie;
	
	/**
	 * Used to extract passwords from the configuration file
	 */
	private final static Pattern passwordPattern = Pattern.compile("(\"password\"\\s*:\\s*\")([^\"]*)(\")");

	/**
	 * Provides the function names and parameter list in code templates
	 */
	private final static Pattern credentialSplitPattern = Pattern.compile("^([^\\:]+)\\:(.+)");

	/**
	 * Provides the function names and parameter list in code templates
	 */
	private final static Pattern functionNamePattern = Pattern.compile("\\s*function\\s+(\\w+)\\s*(\\(.*\\))\\s*\\{", Pattern.MULTILINE);

	/**
	 * Detects change log in channel descriptions with the following format: <change date in format yyyyDDmm>: <change description in 1 line>
	 */
	private final static Pattern changesPattern = Pattern.compile("(\\d{8})\\:? *\\s*(.+)[\\s ]*");

	/**
	 * Detects configurations of external system interfaces that are used for the visualization of the communication between IT systems via Mirth
	 * (This is another in-house tool guys, you can ignore it ;-) )
	 */
	private final static Pattern systemInterfacePattern = Pattern.compile("(IN|OUT)\\:(\\d+|\\*)\\:([^\\:]+)\\:([^\\:\\r\\n]+)(?::([^\\:\\r\\n]+))?");

	/**
	 * Provides function names of functions that are used within code
	 */
	private final static Pattern functionReferenceDetectionPattern = Pattern.compile("[^\\w\\.\\\\](\\w+)(?=\\()");
	/**
	 * Very rough detection of regular expressions. It should be exact enough for filtering false positives at function detection
	 */
	private final static Pattern roughRegexDetectionPattern = Pattern.compile("/[^/\\r\\n]+/");
	/**
	 * Detects all comments in code
	 */
	private final static Pattern base64DetectionPattern = Pattern.compile("(encoding=\"base64\"\\>)[^<]+");
	/**
	 * Detects all comments in code
	 */
	private final static Pattern commentDetectionPattern = Pattern.compile("(?ms)/(?:/.*?$|\\*.*?\\*/)");
	/**
	 * Detects all javascript strings (string in apostrophes) in code
	 */
	private final static Pattern javascriptStringDetectionPattern = Pattern.compile("\\&apos;.*?\\&apos;");
	/**
	 * Detects all java strings (quoted strings) in code
	 */
	private final static Pattern javaStringDetectionPattern = Pattern.compile("\\&quot;.*?\\&quot;");
	/**
	 * Detects all instantiations of new objects (like "new String()")
	 */
	private final static Pattern instantationDetectionPattern = Pattern.compile("\\bnew\\s+([a-zA-Z10-9]+)\\s*\\(");
	/**
	 * Detects all regular expressions
	 */
	private final static Pattern regexDetectionPattern = Pattern.compile(
			"\\/((?![*+?])(?:[^\\r\\n\\[/\\\\]|\\\\.|\\[(?:[^\\r\\n\\]\\\\]|\\\\.)*\\])+)\\/((?:g(?:im?|mi?)?|i(?:gm?|mg?)?|m(?:gi?|ig?)?)?)");
	/**
	 * Detects the id of a component
	 */
	private final static Pattern idPattern = Pattern.compile("<id>([^<]+)<\\/id>");
	/**
	 * Detects all descriptions
	 */
	private final static Pattern descriptionTagDetectionPattern = Pattern.compile("<description>.*?</description>", Pattern.DOTALL);
	/**
	 * Detects all subject tags
	 */
	private final static Pattern subjectTagDetectionPattern = Pattern.compile("<subject>.*?</subject>", Pattern.DOTALL);
	/**
	 * Detects all name tags
	 */
	private final static Pattern nameTagDetectionPattern = Pattern.compile("<name>.*?</name>", Pattern.DOTALL);
	/**
	 * Detects all empty tags (often used for encapsulating SQL)
	 */
	private final static Pattern emptyTagDetectionPattern = Pattern.compile("\\&lt;\\&gt;.*?\\&lt;/\\&gt;", Pattern.DOTALL);
	/**
	 * Detects all SQL queries w/i CDATA tags
	 */
	private final static Pattern cdataDetectionPattern = Pattern.compile("\\&lt;\\!\\[CDATA\\[[\\s\\S]*?\\]\\]\\&gt;", Pattern.DOTALL);

	/**
	 * Detects all SQL queries w/i query tags
	 */
	private final static Pattern queryDetectionPattern = Pattern.compile("\\&lt;query\\&gt;.*?\\&lt;\\/query\\&gt;", Pattern.DOTALL);

	/**
	 * Detects all descriptions
	 */
	private final static Pattern selectTagDetectionPattern = Pattern.compile("<select>.*?</select>", Pattern.DOTALL);
	/**
	 * This pattern is used to extract the JavaScript Doc header (group 1) as well as the name (group 2) from a function
	 */

	private final static Pattern functionHeaderPattern = Pattern
			.compile("(?:\\/\\*\\*((?:[\\s\\S](?!\\*\\/))*.)\\*\\/\\s*){0,1}function\\s*([^\\s\\(]+)\\s*\\(([^\\)]*)\\)", Pattern.DOTALL);

	/**
	 * This pattern is used to extract the JavaScript Doc header (group 1) from a code template that does not contain functions
	 */
	private final static Pattern codeTemplateHeaderPattern = Pattern.compile("\\/\\*\\*((?:[\\s\\S](?!\\*\\/))*.)\\*\\/", Pattern.DOTALL);
	/**
	 * Provides the function description from the JavaScript Doc header
	 */
	private final static Pattern codeTemplateHeaderDescriptionPattern = Pattern.compile("^[\\p{Cntrl}\\s]+([^@]*)");
	/**
	 * Provides the Parameter descriptions from the JavaScript Doc header
	 */
	private final static Pattern codeTemplateHeaderParameterPattern = Pattern.compile("@param\\s+(?:\\{.*?\\})?\\s*?([^@\\s]+)([^@]+)");
	/**
	 * Extracts the parameters from a function definition
	 */
	private final static Pattern functionParameterPattern = Pattern.compile("[^\\s ,]+");

	/**
	 * Provides the return value description from the JavaScript Doc header
	 *
	 */
	private final static Pattern codeTemplateHeaderReturnValuePattern = Pattern.compile("@return\\s+(?:\\{.*?\\})?\\s*?([^@]+)");
	// private final static Pattern CodeTemplateDescriptionPattern = Pattern.compile("\\/\\*\\*([^@\\*]*)");
	/**
	 * This pattern is used to find all occurrences of a components version.
	 */
	private final static Pattern mirthVersionConversionPattern = Pattern.compile("version=\\\"[^\\\"]*\\\"");

	/**
	 * This pattern is used to find a status code
	 */
	private final static Pattern statusPattern = Pattern.compile("<status>([^<]+)<\\/status>");

	/**
	 * This pattern is used to find an id encapsulated in a string tag. This is usually a channel id.
	 */
	private final static Pattern stringPattern = Pattern.compile("<string>([^<]+)<\\/string>");

	/**
	 * This pattern is used to find a status message
	 */
	private final static Pattern messagePattern = Pattern.compile("<message>([^<]+)<\\/message>");

	/**
	 * This pattern is used to find a channel tag
	 */
	private final static Pattern channelTagPattern = Pattern.compile("<channelTag[\\s\\S]*?<\\/channelTag>");

	/**
	 * This pattern is used to find a inter-channel dependency
	 */
	private final static Pattern interChannelDependencyPattern = Pattern.compile("<channelDependency[\\s\\S]*?<\\/channelDependency>");

	/**
	 * This pattern is used to find inter-channel dependency details: group 1 indicates the channel that depends on another channel; group 2 indicates the channel that must be started first 
	 */
	private final static Pattern interChannelDependencyDetailsPattern = Pattern.compile("<dependentId>([^<]+)</dependentId>[\\s]*<dependencyId>([^<]+)</dependencyId>");
	
	/**
	 * This pattern is used to find a channel pruning configuration
	 */
	private final static Pattern pruningPattern = Pattern.compile("<entry[\\s\\S]*?<\\/entry>");

	/**
	 * This pattern is used to find a channel pruning configuration
	 */
	private final static Pattern externalResourcesPattern = Pattern.compile("<resourceIds[\\s\\S]*?<\\/resourceIds>");

	/**
	 * This pattern is used to find a channel pruning configuration
	 */
	private final static Pattern externalResourceEntityPattern = Pattern.compile("<entry>[\\s\\S]*?<string>([^<]*)</string>[\\s\\S]*?<string>([^<]*)</string>[\\s\\S]*?</entry>");
	
	/**
	 * This pattern is used to find channel references
	 */
	private final static Pattern channelIdPattern = Pattern.compile("<channelIds[\\s\\S]*?<\\/channelIds>");

	/**
	 * This pattern is used to find channel definitions
	 */
	private final static Pattern channelPattern = Pattern.compile("<channel [\\s\\S]*?<\\/channel>");

	/**
	 * This pattern is used to find channel references in code template libraries
	 */
	private final static Pattern channelReferencesPattern = Pattern.compile("<enabledChannelIds[\\s\\S]*?<\\/enabledChannelIds>");

	/**
	 * This pattern is used to find code template definitions
	 */
	private final static Pattern codeTemplatePattern = Pattern.compile("<codeTemplate [\\s\\S]*?<\\/codeTemplate>");

	/**
	 * This pattern is used to find code template references in code template libraries
	 */
	private final static Pattern codeTemplateReferencesPattern = Pattern.compile("<codeTemplates[\\s\\S]*?<\\/codeTemplates>");

	// get channel group
	private final static Pattern channelGroupPattern = Pattern.compile("<channelGroup[\\s\\S]*?<\\/channelGroup>");
	// get code template library
	private final static Pattern codeTemplateLibraryPattern = Pattern
			.compile("<codeTemplateLibrary version=\"[^\"]+\">(?:(?!codeTemplateLibrary)[\\s\\S])*</codeTemplateLibrary>");
	// get channel group id (group 1):
	// private final static Pattern groupIdPattern = Pattern.compile("<id>([\\s\\S]*?)<\\/id>");
	// get channel group name (group 1)
	private final static Pattern namePattern = Pattern.compile("<name>([\\s\\S]*?)<\\/name>");
	// get the channels section of the channel group
	private final static Pattern channelGroupChannelsPattern = Pattern.compile("<channels>([\\s\\S]*?)<\\/channels>");
	// get the codeTemplates section of the code template library
	private final static Pattern codeTemplateLibraryCodeTemplatesPattern = Pattern.compile("<codeTemplates>([\\s\\S]*?)<\\/codeTemplates>");
	// a pattern used to separate the original id (of the source system) from the new id (for the destination system because of a detected id collision)
	private final static Pattern idSeparatorPattern = Pattern.compile("([^\\:]+):(.+)");
	
	private static Logger logger = null;
	private static JsonParser jsonParser = null;

	// maps code template meta information to the code template id
	private HashMap<String, JSONObject> codeTemplateInfo = null;
	// maps code template name to code template id
	private HashMap<String, String> codeTemplateIdbyName = null;
	// maps code template id to code template name
	private HashMap<String, String> codeTemplateNameById = null;
	// maps code template Id to the names of the functions that reside in this code template
	private HashMap<String, HashSet<String>> codeTemplateIdToFunction = null;
	// maps channel meta information to the channel id
	private HashMap<String, JSONObject> channelInfo = null;
	// maps a channel to a list of libraries that are referenced by it
	private HashMap<String, ArrayList<String>> channelCodeTemplateLibraryReferences = null;
	// Information about the state of all channels (activated or deactivated)
	private HashMap<String, Boolean> channelState = null;
	// Information about the last modified date in milliseconds for every channel identified by it's id
	private HashMap<String, Long> channelLastModified = null;
	// a list of functions used by a channel and properly linked
	private HashMap<String, ArrayList<String>> channelFunctionReferences = null;
	// a list of functions that are defined within the channel itself
	private HashMap<String, ArrayList<String>> channelInternalFunctionsByChannelId = null;
	// a list of functions that are referenced by a channel but of which the definition could not be identified (in case of false positives these have to be added to the filter list)
	private HashMap<String, TreeSet<String>> unknownChannelFunctions = null;
	// a list of functions that are referenced by a function but of which the definition could not be identified (in case of false positives these have to be added to the filter list)
	private HashMap<String, TreeSet<String>> unknownFunctionFunctions = null;
	// Resolves a channel name to it's id
	private HashMap<String, String> channelIdbyName = null;
	// Resolves a channel id to it's name
	private HashMap<String, String> channelNameById = null;
	// a list of channels that are actually using a function
	private HashMap<String, TreeSet<String>> channelReferencesToFunction = null;
	// a list of functions that are actually using a function
	private HashMap<String, TreeSet<String>> functionLinkedByFunctions = null;
	// a list of functions that are used by a function
	private HashMap<String, ArrayList<String>> functionUsesFunctions = null;
	// A link between functions and the code template to which they belong
	private HashMap<String, String> codeTemplateIdByFunctionName = null;
	// maps channel group meta information to the channel group id
	private HashMap<String, JSONObject> channelGroupInfo = null;
	// provides channel groups in alphabetical order
	private TreeMap<String, String> channelGroupOrder = null;
	// maps code template library meta information to the code template library id
	private HashMap<String, JSONObject> codeTemplateLibraryInfo = null;
	// maps a code template id to a code template library id
	private HashMap<String, String> codeTemplateLibraryIdByCodeTemplateId = null;
	// provides code template libraries in alphabetical order
	private TreeMap<String, String> codeTemplateLibraryOrder = null;
	// provides information about all external resources that are referenced by the mirth instance
	private HashMap<String, JSONObject> externalResources = null;
	// stores inter-channel dependencies - ToDo: still has to be implemented
	private HashMap<String, JSONObject> interChannelDependencies = null;
	// stores detected code template conflicts
	private HashMap<String, HashMap<String, Integer>> functionConflicts = null;
	
	// stores user sessions
	private static final Map<String, HashMap<String, Object>> userSessionCache = Collections.synchronizedMap(new HashMap<String, HashMap<String, Object>>());

	/** Determines the maximum inactivity period of a user session before it will automatically be ended */
	private static Integer userSessionLifeSpanInMinutes = 20;

	/** The point of time at which the configuration has last been loaded */
	private static Long configurationLoadingDate = null;

	static {

		MirthMigrator.logger = LoggerFactory.getLogger(MirthMigrator.class.getName());
		/* usually log level should be set via log4j properties file
		
			For log42 properties this would be:
			===================================
				log4j.logger.lu.hrs.mirth.migration.MirthMigrator = DEBUG
			
			For log4j2 properties this would be:
			====================================
				logger.MirthMigrator.name = lu.hrs.mirth.migration.MirthMigrator
				logger.MirthMigrator.level = DEBUG
		*/
//		MirthMigrator.logger.info("Mirth Migrator version " + getVersion() + " activated");



		// instantiate a JsonParser for transferring JSON-structures to JavaScript
		Context context = (new ContextFactory()).enterContext();
		Scriptable scriptable = context.initStandardObjects();
		MirthMigrator.jsonParser = new JsonParser(context, scriptable);
	}

	public static String getVersion() {
		return MirthMigrator.version;
	}

	/**
	 * Creates a Mirth client instance
	 * 
	 * @param systemName
	 *            The name of this Mirth instance
	 * @param environment
	 *            The Mirth environment to which this Mirth instance belongs
	 * @param server
	 *            The name of the server at which the Mirth service is located
	 * @param port
	 *            The port under which the server will be accessible
	 * @param user
	 *            The predefined user name to log in with
	 * @param password
	 *            The predefined password to log in with
	 * @param description
	 *            A description of the Mirth system
	 * @throws ServiceUnavailableException
	 */
	private MirthMigrator(String systemName, String environment, String server, int port, String user, String password, String description)
			throws ServiceUnavailableException {

		setSystemName(systemName);
		setEnvironment(environment);
		setServer(server);
		setPort(port);
		setUsername(user);
		setPassword(password);
		setDescription(description);

		// disable certificate validation
		trustAll();

		// create a hash value for the relevant server parameters (is intented to be used for smart config reload whenever I get some time)
		setHash(createHash(String.format("%s_%d_%s_%s", server, port, user, password)));
	}

	/**
	 * This function overrides the TrustManager, so that the self-signed certificate of Mirth is not validated.
	 */
	private static void trustAll() {
		SSLContext sc;
		try {
			sc = SSLContext.getInstance("TLS");
			sc.init(null, new TrustManager[] { new TrustAllX509TrustManager() }, new java.security.SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
			HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
				public boolean verify(String string, SSLSession ssls) {
					return true;
				}
			});
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (KeyManagementException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Validates a provided user account against a Mirth instance and creates a user session if the validation process was successful
	 * 
	 * @param credentials
	 *            The encrypted user credentials
	 * @return The session id or null if session could not be created
	 * @throws ServiceUnavailableException
	 */
	public static String createUserSession(String credentials) throws ServiceUnavailableException {

		// split the credentials
		Matcher credentialSplitMatcher = credentialSplitPattern.matcher(decrypt(credentials));
		if (credentialSplitMatcher.find()) {
			// get username
			String username = credentialSplitMatcher.group(1);
			// and password
			String password = credentialSplitMatcher.group(2);

			// create the user session
			return createUserSession(username, password, null, null);
		} else {
			return null;
		}
	}

	/**
	 * Encrypts a string
	 * @param text The text that should be encrypted
	 * @return The encrypted string
	 */
    public static String encrypt(String text) {
        String key = MirthMigrator.CREDENTIALS_KEY;
        StringBuilder encrypted = new StringBuilder();
        
        for (int i = 0; i < text.length(); i++) {
        	// encrypt by XORing each character
            int charCode = (text.charAt(i) ^ key.charAt(i % key.length())) % 255;
            // add the encrypted char to the final result by assuring 2 characters are used per character
            encrypted.append(String.format("%02x", charCode));
        }
        
        return encrypted.toString();
    }
	
	/**
	 * Decrypts a string
	 * 
	 * @param encryptedText
	 *            The encrypted string
	 * @return The decrypted string
	 */
	private static String decrypt(String encryptedText) {
		String key = MirthMigrator.CREDENTIALS_KEY;

		StringBuilder decrypted = new StringBuilder();
		// extract hex values
		Pattern pattern = Pattern.compile(".{1,2}");
		Matcher matcher = pattern.matcher(encryptedText);

		int index = 0;
		// convert all values
		while (matcher.find()) {
			// get next hex value
			String hexChunk = matcher.group();
			// decode it (simple XOR "encryption")
			int charCode = Integer.parseInt(hexChunk, 16) ^ key.charAt(index++ % key.length()) % 255;
			// and add it to the decoded string
			decrypted.append((char) charCode);
		}

		// return the decrypted string
		return decrypted.toString();
	}

	/**
	 * Validates a provided user account against a Mirth instance and creates a user session if the validation process was successful
	 * 
	 * @param username
	 *            The name of the user for whom the session is requested.
	 * @param password
	 *            The password of the user for whom the session is requested
	 * @return The session id or null if session could not be created
	 * @throws ServiceUnavailableException
	 */
	public static String createUserSession(String username, String password) throws ServiceUnavailableException {
		return createUserSession(username, password, null, null);
	}

	/**
	 * Validates a provided user account against a Mirth instance and creates a user session if the validation process was successful
	 * 
	 * @param username
	 *            The name of the user for whom the session is requested.
	 * @param password
	 *            The password of the user for whom the session is requested
	 * @param serverPort
	 *            A port at which the Mirth service is listening<br/>
	 *            <i>(OPTIONAL - default: 8443)</i>
	 * @return The session id or null if session could not be created
	 * @throws ServiceUnavailableException
	 */
	public static String createUserSession(String username, String password, Integer serverPort) throws ServiceUnavailableException {
		return createUserSession(username, password, serverPort, null);
	}

	/**
	 * Validates a provided user account against a Mirth instance and creates a user session if the validation process was successful
	 * 
	 * @param username
	 *            The name of the user for whom the session is requested.
	 * @param password
	 *            The password of the user for whom the session is requested
	 * @param serverPort
	 *            A port at which the Mirth service is listening<br/>
	 *            <i>(OPTIONAL - default: 8443)</i>
	 * @param serverName
	 *            The name of the Mirth server against which the user account should be validated. The user must exist for the Mirth service at this
	 *            server<br/>
	 *            <i>(OPTIONAL - default: localhost)</i>
	 * @return The session id or null if session could not be created
	 * @throws ServiceUnavailableException
	 */
	public static String createUserSession(String username, String password, Integer serverPort, String serverName)
			throws ServiceUnavailableException {
		String userSessionCookie = null;
		if ((serverName == null) || (serverName.isEmpty())) {
			// by default use the Mirth instance that runs MirthMigrator
			serverName = "localhost";
		}
		if (serverPort == null) {
			// by default use the Mirth standard port
			serverPort = 8443;
		}
		trustAll();
		// try to authenticate
		JSONObject login = login(serverName, serverPort, username, password);
		// get the indicator if login was successful
		boolean loginSuccessful = login.getBoolean("loginSuccessful");
		// if the login was successful
		if (loginSuccessful) {
			// get the user session cookie
			userSessionCookie = login.getString("sessionCookie").replaceAll(";Path=/api;Secure", "");
			// log the user out again
			logout(userSessionCookie, serverPort, serverName);
		}

		synchronized (MirthMigrator.userSessionCache) {
			// check if there is still an active session in the cache. This avoids double session timeout in case of concurrent ajax requests
			Iterator<Map.Entry<String, HashMap<String, Object>>> iterator = MirthMigrator.userSessionCache.entrySet().iterator();
			while (iterator.hasNext()) {
				// check next session
				HashMap<String, Object> session = iterator.next().getValue();
				if (logger.isDebugEnabled()) {
					logger.debug("Checking session \"" + session.get("sessionCookie") + "\"");
				}
				// if the session contains the username and is still valid
				if ((session.get("sessionCookie") != null) && session.get("username").equals(username)
						&& (System.currentTimeMillis() - ((long) session.get("lastAccess")) <= getUserSessionLifeSpan() * 60000)) {
					// reset the session life
					session.put("lastAccess", System.currentTimeMillis());
					String backup = userSessionCookie;
					// and reuse this session
					userSessionCookie = (String) session.get("sessionCookie");
					if (logger.isDebugEnabled()) {
						logger.debug("Recycled session \"" + userSessionCookie + "\". New session \"" + backup + "\" will be discarded");
					}
					// work is done
					break;
				}
			}
		}
		// store the session cookie (and assure that any pre-existing session of the very user is terminated)
		setUserSession(username, userSessionCookie, true);
		
		return userSessionCookie;
	}

	/**
	 * Establishes a mirth client session for a mirth server. In case of success the session cookie is stored in the client instance.
	 * 
	 * @return true, if the login was successful, false otherwise
	 * @throws ServiceUnavailableException
	 */
	private boolean createServerSession() throws ServiceUnavailableException {

		// try to authenticate
		JSONObject login = login(getServer(), getPort(), getUsername(), getPassword());
		// get the indicator if login was successful
		boolean loginSuccessful = login.getBoolean("loginSuccessful");
		// if the login was successful
		if (loginSuccessful) {
			// store the session cookie
			setServerSessionCookie(login.getString("sessionCookie"));
		}

		return loginSuccessful;
	}

	/**
	 * Tries to authenticate a user at a mirth instance and open a session.
	 * 
	 * @param serverName
	 *            The name of the server the mirth service is running on
	 * @param Port
	 *            The port under which the mirth service is listening
	 * @param userName
	 *            The user that should be authenticated
	 * @param password
	 *            The password of the user that should be authenticated
	 * @return A JSON object containing the following attributes:
	 *         <ul>
	 *         <li><b>responseCode</b> - The HTTP response code</li>
	 *         <li><b>loginSuccessful</b> - true if the login was successful, false otherwise</li>
	 *         <li><b>responseMessage</b> - A response message detailing the response status (just in case of failure so far)</li>
	 *         <li><b>sessionCookie</b> - The cookie identifying the active session</li>
	 *         </ul>
	 * @throws ServiceUnavailableException
	 *             If the Mirth instance is not available
	 */
	private static JSONObject login(String serverName, int Port, String userName, String password) throws ServiceUnavailableException {

		JSONObject result = new JSONObject();
		result.put("responseCode", 500);
		result.put("responseMessage", "Internal Error - this was not expected");

		byte[] postDataBytes = null;

		// open the connection
		HttpURLConnection urlConnection = connectToRestService(serverName, Port, "/api/users/_login");

		// create post parameters
		Map<String, Object> parameters = new LinkedHashMap<>();

		try {
			parameters.put("username", userName);
			parameters.put("password", password);
			StringBuilder postData = new StringBuilder();
			// assemble post parameters
			for (Map.Entry<String, Object> parameter : parameters.entrySet()) {
				if (postData.length() != 0)
					postData.append('&');
				postData.append(URLEncoder.encode(parameter.getKey(), "UTF-8"));
				postData.append('=');
				postData.append(URLEncoder.encode(String.valueOf(parameter.getValue()), "UTF-8"));
			}
			postDataBytes = postData.toString().getBytes(StandardCharsets.UTF_8);
		} catch (UnsupportedEncodingException e2) {
		}

		urlConnection.setDoOutput(true);

		try {
			urlConnection.setRequestMethod("POST");
		} catch (ProtocolException e1) {
		}
		urlConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
		urlConnection.setRequestProperty("Accept", "application/xml");

		try {
			try {
				// and also the encoded parameters
				urlConnection.getOutputStream().write(postDataBytes);
			} catch (SocketTimeoutException e) {
				logger.error("Service at " + urlConnection.getURL().getHost() + ":" + urlConnection.getURL().getPort() + " is currently not available: "
						+ e.getMessage());
				// indicate the unavailable service also in the response
				result.put("responseCode", 503);
				result.put("loginSuccessful", false);
				result.put("responseMessage",
						"Service at " + urlConnection.getURL().getHost() + ":" + urlConnection.getURL().getPort() + " is currently not available");
				urlConnection.disconnect();
				return result;
			}

			// handle response
			result = readResponse(urlConnection);
			String responseMessage = result.getString("responseMessage");
			// remember the response code
			result.put("responseCode", urlConnection.getResponseCode());
			// set a flag that indicates if the login was successful
			Matcher statusMatcher = statusPattern.matcher(responseMessage);
			result.put("loginSuccessful", statusMatcher.find() && statusMatcher.group(1).equalsIgnoreCase("SUCCESS"));
			// remember the status message, if any (usually just in case of failure)
			Matcher messageMatcher = messagePattern.matcher(responseMessage);
			result.put("responseMessage", messageMatcher.find() ? statusMatcher.group(1) : "");
			// remember the session cookie if the request was successful
			result.put("sessionCookie",
					(urlConnection.getResponseCode() == 200) ? urlConnection.getHeaderField("Set-Cookie").replaceAll(";Path=/api;Secure", "") : "");
		} catch (IOException e) {// internal server error (default) will be returned if this happens
		}

		try {
			// close the connection
			urlConnection.disconnect();
		} catch (Exception e) {
			// silent close
		}

		return result;
	}

	/**
	 * Ends a session with the Mirth service
	 * 
	 * @param sessionCookie
	 *            The identifier of the session that should be terminated
	 * @param serverPort
	 *            The port of the Mirth service
	 * @param serverName
	 *            The name of the server at which the mirth service is located
	 */
	private static void logout(String sessionCookie, Integer serverPort, String serverName) {
		try {

			// open the connection
			HttpURLConnection urlConnection = connectToRestService(serverName, serverPort, "/api/users/_logout");
			urlConnection.setUseCaches(false);
			urlConnection.setRequestMethod("POST");
			urlConnection.setRequestProperty("X-Requested-With", MirthMigrator.clientIdentifier);
			urlConnection.setRequestProperty("Cookie", sessionCookie);
			urlConnection.setRequestProperty("Cache-Control", "no-cache");

		} catch (Exception e) {
			logger.error("Exception: " + e.getMessage());
		}
	}

	/**
	 * Provides a timestamp as a human readable date string
	 * 
	 * @param timestamp
	 *            The date in milliseconds
	 * @return The date in the following format: <b>dd.MM.yyyy, HH:mm:ss</b>
	 */
	private static String formatDate(long timestamp) {
		return (timestamp > 0) ? displayDate.format(new Date(timestamp)) : "-";
	}

	/**
	 * Retrieves all relevant information from Mirth. It builds up a new JSONObject, only containing the informations that the client side needs. The
	 * returned information will be used for populating the tables of the source and the destination system. For the metadata table and the content
	 * section another function {@link #getComponentDetails(String, String)} will be consumed.
	 * 
	 * @param groupType
	 *            Either {@link #CHANNEL_GROUP} or {@link #CODE_TEMPLATE_LIBRARY}
	 * @return A JSON object with the following structure:
	 *         <ul>
	 *         <li><b>success</b> - The status that indicates if the operation was successful (true) or not (false)</li>
	 *         <li><b>statusCode</b> - The HTTP return code (e.g. 200 in case of success)</li>
	 *         <li><b>payload</b> - the actual payload of the request with the following structure: A JSONObject of channel-groups or code template
	 *         libraries</li>
	 *         </ul>
	 *         If the request was not successful (success = false), the payload usually only consists of an error message
	 */
	public NativeObject getMetaData(String groupType) {
		return getMetaData(groupType, false);
	}

	/**
	 * Retrieves all relevant information from Mirth. It builds up a new JSONObject, only containing the informations that the client side needs. The
	 * returned information will be used for populating the tables of the source and the destination system. For the metadata table and the content
	 * section another function {@link #getComponentDetails(String, String)} will be consumed.
	 * 
	 * @param groupType
	 *            Either {@link #CHANNEL_GROUP} or {@link #CODE_TEMPLATE_LIBRARY}
	 * @param refresh
	 *            If this flag is set, the configuration of this system will be reloaded before the answer is generated
	 * @return A JSON object with the following structure:
	 *         <ul>
	 *         <li><b>success</b> - The status that indicates if the operation was successful (true) or not (false)</li>
	 *         <li><b>statusCode</b> - The HTTP return code (e.g. 200 in case of success)</li>
	 *         <li><b>payload</b> - the actual payload of the request with the following structure: A JSONObject of channel-groups or code template
	 *         libraries</li>
	 *         </ul>
	 *         If the request was not successful (success = false), the payload usually only consists of an error message
	 */
	public NativeObject getMetaData(String groupType, boolean refresh) {

		// determine group type (channel group or code template library)
		boolean isChannelGroup = (CHANNEL_GROUP.equals(groupType));

		try {
			// if a refresh was requested
			if (refresh) {
				// empty the configuration of this instance
				forceRefresh();
			}

			JSONObject metaData = isChannelGroup ? getChannelGroupMetaData() : getCodeTemplateLibraryMetaData();
			return createReturnValue(200, metaData);

		} catch (ConfigurationException e) {
			// an invalid configuration was detected
			return createReturnValue(500, "Corrupt configuration (either in \"" + configurationFileLocation + "\" or in the " + CHANNEL_GROUP
					+ " definitions in the Mirth instance \"" + getServer() + "\" itself.");
		} catch (ServiceUnavailableException e) {
			// the target system is not available
			return createReturnValue(503, e.getMessage());
		}

	}

	/**
	 * Provides meta data information of all channel groups and assigned channels
	 * 
	 * @return Meta data information of all channel groups and assigned channels
	 * @throws ServiceUnavailableException
	 * @throws ConfigurationException
	 */
	private JSONObject getChannelGroupMetaData() throws ConfigurationException, ServiceUnavailableException {
		return getChannelGroupMetaDataById(null);
	}

	/**
	 * Provides meta data information of a specific channel group and assigned channels
	 * 
	 * @param id
	 *            The id of the desired channel group. <i>(OPTIONAL - If null, the meta data of all groups will be provided)</i>
	 * @return Meta data information of all or a specific channel group and assigned channels:
	 * 
	 * 
	 */
	private JSONObject getChannelGroupMetaDataById(String id) throws ConfigurationException, ServiceUnavailableException {
		JSONObject metaData = new JSONObject();

		// no specific id means all code template libraries
		if (id == null) {
			// get all channel groups in alphabetical order
			for (String channelGroupId : getChannelGroupList()) {
				// get the meta data for the current channel group
				JSONObject currentGroup = getChannelGroupInfoById(channelGroupId);
				// and add it to the structure
				metaData.accumulate("item", currentGroup);
				// now add the meta-data of all child elements
				if (currentGroup.has("Members")) {
					for (Object channelId : currentGroup.getJSONArray("Members")) {
						// add the current element to the structure
						metaData.accumulate("item", getChannelMetaDataById((String) channelId));
					}
					// indicate how many channels a group possesses
					metaData.put("Number of members", currentGroup.get("Number of members"));
				} else {
					// no channels in this group
					metaData.put("Number of members", 0);
				}
			}
			// add info about the total number of groups (the group of unassigned channels is not a real group)
			metaData.put("Number of groups", getChannelGroupInfo().size() - 1);
			// add info about the total number of channels
			metaData.put("Number of members", getChannelInfo().size());
		} else {
			// get the metadata for the current code template library
			JSONObject currentGroup = getChannelGroupInfoById(id);
			// and add it to the structure
			metaData.accumulate("item", currentGroup);

			if (currentGroup.has("Members")) {
				// now add the meta-data of all child elements
				for (Object channelId : currentGroup.getJSONArray("Members")) {
					// add the current element to the structure
					metaData.accumulate("item", getChannelMetaDataById((String) channelId));
				}
			}
			// add info about the total number of groups (the group of unassigned channels is not a real group)
			metaData.put("Number of groups", (currentGroup.getString("Id").indexOf(' ') < 0) ? 1 : 0);
			// add info about the total number of channels
			metaData.put("Number of members", currentGroup.get("Number of members"));
		}

		// remember the mirth version
		metaData.put("Mirth version", getMirthVersion().getVersionString());
		if (metaData.has("item") && (metaData.get("item") instanceof JSONObject)) {
			JSONArray itemGroup = new JSONArray().put(metaData.get("item"));
			metaData.remove("item");
			metaData.put("item", itemGroup);
		}

		return metaData;
	}

	/**
	 * Provides a Mirth client identified by it's name. Mirth clients are cached. If the clients are not cached at the point of time of the request or
	 * if a reload is explicitly requested, the configuration is loaded and applied.<br/>
	 * <br/>
	 * The configuration consists of 3 sections:
	 * <ul>
	 * <li><b>environment</b> - A list of Mirth environments:
	 * <ul>
	 * <li><b>id</b> - the unique identifier of the environment</li>
	 * <li><b>name</b> - the name of the environment</li>
	 * <li><b>color</b> - a color that will be used to show the mirth services that are assigned to this environment</li>
	 * </ul>
	 * </li>
	 * <li><b>system</b> - A list of mirth systems:
	 * <ul>
	 * <li><b>name</b> - The display name for this system</li>
	 * <li><b>server</b> - the name or ip that is used to access the system</li>
	 * <li><b>environment</b> - a reference to the id of the environment to which the system should be assigned</li>
	 * <li><b>description</b> - a description of the system</li>
	 * <li><b>user</b> - a username to access the mirth service of the system</li>
	 * <li><b>password</b> - the password that correspond to the user</li>
	 * <li><b>port</b> - the port that is used to access the system</li>
	 * </ul>
	 * </li>
	 * <li><b>excludeFromFunctionDetection</b> - A list of terms that should be excluded from automated function detection</li>
	 * </ul>
	 * The configuration file location is defined by {@link #configurationFileLocation}<br/>
	 * <br/>
	 *
	 * @return The Mirth client instance
	 * @throws IOException
	 *             If the configuration file could not be loaded
	 * @throws JSONException
	 *             If the configuration file format is invalid (no valid JSON)
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 */
	private static void loadConfiguration() throws IOException, ConfigurationException, ServiceUnavailableException {

		// truncate caches
		MirthMigrator.mirthClients = null;
		MirthMigrator.mirthEnvironments = null;

		File configFile = new File(configurationFileLocation);
		// check if there is a configuration file
		if (!configFile.exists()) {
		
			// finally indicate that configuration is needed before anything else can be done
			throw new ConfigurationException("The Mirth Migrator configuration file \"" + configFile.getAbsolutePath() + "\" is missing.");
		}
		
		// load the configuration file
		String rawConfiguration = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);

		Matcher passwordMatcher = passwordPattern.matcher(rawConfiguration);
		StringBuffer decryptedConfig = new StringBuffer();

		// make sure that all passwords are decrypted in memory
        while (passwordMatcher.find()) {
            String decryptedPassword = passwordMatcher.group(2); // Extract the password
            
			try {
				// try to decrypt the password
				decryptedPassword = decrypt(decryptedPassword);
			} catch (Exception e) {
				// decryption failed so it seems to be a plain password already
				logger.warn("Detected unencrypted password!");
			}

            // replace the plain-text password with the encrypted password
            passwordMatcher.appendReplacement(decryptedConfig, passwordMatcher.group(1) + decryptedPassword + passwordMatcher.group(3));
        }
        // and add the remaining part
        passwordMatcher.appendTail(decryptedConfig);
        
        // parse the JSON file
		JSONObject configuration = new JSONObject(decryptedConfig.toString());
		// cache the configuration
		MirthMigrator.configuration = configuration;

		/* 1. Parse the environment configuration */

		if (!configuration.has("environment")) {
			throw new ConfigurationException(
					"The configuration file does not contain the \"environment\"-section that defines the integration environments.");
		}

		// access the definition of the Mirth systems
		JSONArray environments = (configuration.get("environment") instanceof JSONArray) ? configuration.getJSONArray("environment")
				: new JSONArray().put(configuration.getJSONObject("environment"));

		// Read in the configuration of all Mirth clients
		for (Object element : environments) {
			// scan next system configuration
			JSONObject environment = (JSONObject) element;

			String environmentId, environmentPosition, environmentName, environmentColor;

			if (!environment.has("id")) {
				logger.error("SKIPPING: The Mirth Migrator configuration contains a integration environment definition without id: \n" + environment);
				continue;
			}
			environmentId = environment.getString("id");

			if (!environment.has("position")) {
				logger.error("SKIPPING: The Mirth Migrator configuration contains a integration environment definition without position id: \n"
						+ environment);
				continue;
			}
			environmentPosition = environment.getInt("position") + "";

			if (!environment.has("name")) {
				logger.error("SKIPPING: The configuration for the integration environment \"" + environmentId
						+ "\" does not contain the environment name.");
				continue;
			}
			environmentName = environment.getString("name");

			if (!environment.has("color")) {
				logger.error("SKIPPING: The configuration for the integration environment \"" + environmentName
						+ "\" does not contain the environment color.");
				continue;
			}
			environmentColor = environment.getString("color");

			// add the environment to the environment cache
			addEnvironment(environmentId, environmentPosition, environmentName, environmentColor);
		}

		/* 2. Parse the system configuration */

		if (!configuration.has("system")) {
			throw new ConfigurationException("The configuration file does not contain the \"system\"-section that defines the Mirth servers.");
		}

		// access the definition of the Mirth systems
		JSONArray systems = (configuration.get("system") instanceof JSONArray) ? configuration.getJSONArray("system")
				: new JSONArray().put(configuration.getJSONObject("system"));

		// Read in the configuration of all Mirth clients
		for (Object element : systems) {
			String systemName, server, description, user, password, environment;
			int port = 8443;

			// scan next system configuration
			JSONObject system = (JSONObject) element;

			if (!system.has("name")) {
				logger.error("SKIPPING: The Mirth Migrator configuration contains a system definition without name: \n" + system);
				continue;
			}
			systemName = system.getString("name");

			if (!system.has("server")) {
				logger.error("SKIPPING: The configuration for the system \"" + systemName + "\" does not contain the server address.");
				continue;
			}
			server = system.getString("server");

			if (!system.has("port")) {
				logger.warn("The configuration for the system \"" + systemName
						+ "\" does not contain the port at which the Mirth service is listening. Using the default port 8443");
			} else {
				// if port was not set, use the default port
				port = system.getInt("port");
			}

			if (!system.has("environment")) {
				logger.error("SKIPPING: The configuration for the system \"" + systemName
						+ "\" does not contain a reference to the environment to which the system is assigned.");
				continue;
			}

			environment = system.getString("environment");

			if (!hasEnvironment(environment)) {
				logger.error("SKIPPING: The configuration for the system \"" + systemName + "\" references a Mirth environment called \"" + environment
						+ "\", which does not exist.");
				continue;
			}

			// system description is optional
			description = system.has("description") ? system.getString("description") : null;

			if (!system.has("user")) {
				logger.error("SKIPPING: The configuration for the system \"" + systemName + "\" does not contain a username.");
				continue;
			}

			user = system.getString("user");

			if (!system.has("password")) {
				logger.error("SKIPPING: The configuration for the system \"" + systemName + "\" does not contain a password.");
				continue;
			}

			password = system.getString("password");

			// create the client for the mirth instance
			addClient(systemName, environment, server, port, user, password, description);
		}

		/* 3. Parse the function filters */

		// create the filter cache
		functionFilter = new ArrayList<String>();

		// if a filter list was defined
		if (configuration.has("excludeFromFunctionDetection")) {

			// access the list of functions to be excluded from function detection
			JSONArray excludeFunctions = configuration.getJSONArray("excludeFromFunctionDetection");

			// and add all functions that should be filtered
			for (int index = 0; index < excludeFunctions.length(); index++) {
				// add the function to the exclude cache
				functionFilter.add(excludeFunctions.getString(index));
			}
		}

		/* 4. check for user session life-span configuration */

		// if a session lifespan was defined
		if (configuration.has("sessionLifeSpanInMinutes")) {
			int lifeSpan = configuration.getInt("sessionLifeSpanInMinutes");
			if (lifeSpan >= 0) {
				// update the session lifespan
				setUserSessionLifeSpan(lifeSpan);
				logger.debug("Session lifespan has been set to "+getUserSessionLifeSpan()+" minutes");
			} else {
				logger.warn("Configured session lifespan of "+lifeSpan+" minutes is invalid. Using default lifespan of "+MirthMigrator.userSessionLifeSpanInMinutes+" minutes");				
			}
		} else {
			logger.warn("Session lifespan was not found in configuration file. Using default lifespan of "+MirthMigrator.userSessionLifeSpanInMinutes+" minutes");
		}

		/* 5. finally, remember the point of time the configuration has been loaded */
		setConfigurationLoadingDate(System.currentTimeMillis());
	}

	private static MirthMigrator addClient(String systemName, String environment, String server, int port, String user, String password,
			String description) throws ServiceUnavailableException {

		// if configuration was not yet loaded (or removed to force full reload)
		if (MirthMigrator.mirthClients == null) {
			// create the Mirth client cache
			MirthMigrator.mirthClients = new HashMap<String, MirthMigrator>();
		}

		// create the client instance
		MirthMigrator client = new MirthMigrator(systemName, environment, server, port, user, password, description);
		// add it to the cache
		mirthClients.put(systemName.toLowerCase(), client);

		// and also provide it as return value
		return client;
	}

	/**
	 * Checks if a Mirth client exists
	 * 
	 * @param systemName
	 *            The name of the system that identifies the Mirth client
	 * @return true if a Mirth client with the provided system name exists, false otherwise
	 */
	public static boolean hasClient(String systemName) {
		try {
			return getClient(systemName) != null;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Provides a Mirth client instance
	 * 
	 * @param systemName
	 *            The name of the system that identifies the Mirth client
	 * @return The Mirth client instance or null if the requested instance was not found
	 * @throws ConfigurationException
	 * @throws IOException
	 *             If the configuration file could not be loaded
	 * @throws ServiceUnavailableException
	 */
	public static MirthMigrator getClient(String systemName) throws IOException, ConfigurationException, ServiceUnavailableException {
		return getClient(systemName, false);
	}

	/**
	 * Provides a Mirth client instance
	 * 
	 * @param systemName
	 *            The name of the system that identifies the Mirth client
	 * @param forceReload
	 *            If this flag is set, the configuration of the requested client is reloaded
	 * @return The Mirth client instance or null if the requested instance was not found
	 * @throws IOException
	 *             If the configuration file could not be loaded
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 * @throws JSONException
	 *             If the configuration file format is invalid (no valid JSON)
	 */
	public static MirthMigrator getClient(String systemName, boolean forceReload)
			throws IOException, ConfigurationException, ServiceUnavailableException {
		// if configuration was not yet loaded (or removed to force full reload)
		if (MirthMigrator.mirthClients == null) {
			loadConfiguration();
		}

		if ((systemName == null) || systemName.isEmpty()) {
			return null;
		}
		// capitalization must not be of importance
		systemName = systemName.toLowerCase();

		// get the client instance from the cache
		MirthMigrator client = mirthClients.get(systemName);

		// if the configuration has changed
		if (getConfigLastChange() > getConfigurationLoadingDate()) {
			logger.info("Configuration was changed at " + LocalDateTime.ofInstant(Instant.ofEpochMilli(getConfigLastChange()), ZoneId.systemDefault())
					.format(DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm:ss.SSS")) + " ==> reloading configuration");

			// reset the clients list
			MirthMigrator.mirthClients = null;
			// and reload the configuration
			loadConfiguration();
			// indicate to every session that the config has changed and that the app should be reloaded
			MirthMigrator.userSessionCache.values().forEach(session -> session.put("configurationChanged", true));

			// it does not matter that the client might not be up-to-date anymore, as the app will be informed to reload when the session is checked

		} else if (mirthClients.containsKey(systemName) && forceReload) {
			// if the client content should be force-updated
			// initiate the configuration reload
			client.forceRefresh();
		}

		return client;
	}

	/**
	 * Checks if the Mirth Migrator configuration has changed. If the configuration has changed, it will automatically be reloaded once.
	 * 
	 * @param sessionId
	 *            The id of the session for which it should be checked if the configuration has changed.
	 * @return true, if the configuration has changed, false otherwise.<br/>
	 *         <br/>
	 *         <b>ATTENTION: This function only returns true once for every session if a configuration has changed.
	 *         <span style="color:red;">Subsequent calls for the same session will return false</span> till the configuration has changed again</b>
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 * @throws IOException
	 */
	public static synchronized boolean hasConfigurationChanged(String sessionId)
			throws ConfigurationException, IOException, ServiceUnavailableException {
		boolean hasChanged = false, needsLoading = false;
		Long configurationChangeDate = null;
		Long configurationLastLoaded = null;
		String configurationChanged = "configurationChanged";

		configurationChangeDate = getConfigLastChange();
		configurationLastLoaded = getConfigurationLoadingDate();

		// determine if the configuration needs to be loaded
		needsLoading = ((configurationLastLoaded == null) || (configurationChangeDate > configurationLastLoaded));

		// if the configuration has to be (re)loaded
		if (needsLoading) {
			// reset the clients list
			MirthMigrator.mirthClients = null;
			// and (re)load the configuration
			loadConfiguration();

			// determine if configuration has changed (initial loading is ignored here)
			hasChanged = (configurationLastLoaded != null) && (configurationChangeDate > configurationLastLoaded);

			// if the configuration has changed, the clients need to be informed
			if (hasChanged) {
				// indicate to every session that the configuration has changed and that the clients should be reloaded to reflect this change
				MirthMigrator.userSessionCache.values().forEach(session -> session.put(configurationChanged, true));
			}
		}

		// In case of an already active user session
		if (sessionId.startsWith("JSESSIONID")) {
			// get the session
			HashMap<String, Object> session = getUserSession(sessionId);
			// and check if the configurationChanged-flag is set
			if ((session != null) && session.containsKey(configurationChanged)) {
				// remove the indicator from the session
				session.remove(configurationChanged);
				// and set the changed flag
				hasChanged = true;
			}
		} else {
			// a new session was requested for the user. However it might be that this is just a renewal after a session timeout. This would mean
			// the client has to be reloaded nevertheless
			// extract the user name from the session id
			Matcher credentialSplitMatcher = credentialSplitPattern.matcher(decrypt(sessionId));
			if (credentialSplitMatcher.find()) {
				// extract the user name
				String username = credentialSplitMatcher.group(1);

				// check if there is an old session for this user. This would mean the user session had a timeout.
				Iterator<Map.Entry<String, HashMap<String, Object>>> iterator = MirthMigrator.userSessionCache.entrySet().iterator();
				while (iterator.hasNext()) {
					// check next session
					HashMap<String, Object> session = iterator.next().getValue();
					// if the session contains the username and the configurationChanged indicator
					if (session.get("username").equals(username) && session.containsKey(configurationChanged)) {
						// remove the indicator from the session
						session.remove(configurationChanged);
						// and set the changed flag
						hasChanged = true;
						// work is done
						break;
					}
				}
			}
		}

		return hasChanged;
	}

	/**
	 * Determines the point of time of the last change of the configuration file
	 * 
	 * @return The point of time of the last change
	 * @throws ConfigurationException
	 *             If the configuration file is not available
	 */
	private static long getConfigLastChange() throws ConfigurationException {

		long result = -1;

		Path configFile = Paths.get(configurationFileLocation);
		// check if there is a configuration file
		if (Files.notExists(configFile)) {
			// and indicate if this is not the case
			throw new ConfigurationException("The Mirth Migrator configuration file \"" + configFile.toAbsolutePath() + "\" is missing.");
		}

		try {
			result = Files.readAttributes(configFile, BasicFileAttributes.class).lastModifiedTime().toMillis();
		} catch (IOException e) {
			logger.error("Unable to determine last changed date of \"" + configFile.toAbsolutePath() + "\": \n" + e.getMessage());
		}

		return result;
	}

	/**
	 * Creates a new Mirth environment and adds it to the cache.<br/>
	 * <br/>
	 * <i>So far environments are only used to differentiate Mirth instances (e.g. production, test, development, fallback) and to assign a specific
	 * color to the member Mirth instances</i>
	 * 
	 * @param id
	 *            The identifier of the environment
	 * @param position
	 *            The position at which the environment should be displayed
	 * @param name
	 *            The name of the environment
	 * @param color
	 *            The color of the environment
	 * @return The new environment
	 */
	private static HashMap<String, String> addEnvironment(String id, String position, String name, String color) {
		// if configuration was not yet loaded (or removed to force full reload)
		if (MirthMigrator.mirthEnvironments == null) {
			// create the cache
			MirthMigrator.mirthEnvironments = new HashMap<String, HashMap<String, String>>();
		}

		// create the new environment
		HashMap<String, String> environment = new HashMap<String, String>();
		// add the id
		environment.put("id", id);
		// add the display position
		environment.put("position", position);
		// it's name
		environment.put("name", name);
		// and also the display color
		environment.put("color", color);

		// add the new environment to the cache
		MirthMigrator.mirthEnvironments.put(id, environment);

		return environment;
	}

	/**
	 * Checks if a Mirth environment exists
	 * 
	 * @param id
	 *            The id of the Mirth environment that should be checked
	 * @return True, if the Mirth environment exists, false otherwise
	 */
	private static boolean hasEnvironment(String id) {
		try {
			return getEnvironment(id) != null;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Get a Mirth environment from cache
	 * 
	 * @param id
	 *            The id of the requested Mirth environment
	 * @return The requested Mirth environment or null if it was not found
	 * @throws IOException
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 */
	private static HashMap<String, String> getEnvironment(String id) throws ConfigurationException, IOException, ServiceUnavailableException {

		// if configuration was not yet loaded (or removed to force full reload)
		if (MirthMigrator.mirthEnvironments == null) {
			loadConfiguration();
		}
		// get the Mirth environment from the cache
		return mirthEnvironments.get(id);
	}

	/**
	 * Creates a hash value from a string
	 * 
	 * @param string
	 *            the string from which the hash value should be created
	 * @return the hash value as string (not as hex string) or null if the conversion was not possible
	 */
	private static String createHash(String string) {
		String checksum = null;

		try {
			// SHA-256 should be sufficient
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			// create the hash value
			byte[] hashBytes = digest.digest(string.getBytes(StandardCharsets.UTF_8));

			// and now
			StringBuilder hexString = new StringBuilder();
			// transform every byte
			for (byte hashByte : hashBytes) {
				// to it's corresponding hex string
				String hex = Integer.toHexString(0xff & hashByte);
				if (hex.length() == 1)
					hexString.append('0');
				// and combine it with the other bytes
				hexString.append(hex);
			}
			// set the return value
			checksum = hexString.toString();

		} catch (NoSuchAlgorithmException e) {
			// should never happen
			logger.error("Unable to generate checksum: " + e.getMessage());
		}

		return checksum;
	}

	/**
	 * Provides an ordered list of all channel group IDs
	 * 
	 * @return A collection of channel group IDs
	 * @throws ServiceUnavailableException
	 * @throws ConfigurationException
	 */
	private Collection<String> getChannelGroupList() throws ConfigurationException, ServiceUnavailableException {
		if (this.channelGroupInfo == null) {
			// it is important to first generate the information before they are provided to the user
			getChannelGroupInfo();
		}

		return this.channelGroupOrder.values();
	}

	/**
	 * Provides the metadata of a specific channel group identified by it's unique id.<br>
	 * This does <u><i>not</i></u> include the metadata of the contained channels.<br>
	 * <br>
	 * This is is needed, the function {@link #getChannelGroupMetaDataById(String)} should be called
	 * 
	 * @param channelGroupId
	 *            The channel group id
	 * @return The channel group meta data
	 * @throws ServiceUnavailableException
	 * @throws ConfigurationException
	 */
	private JSONObject getChannelGroupInfoById(String channelGroupId) throws ConfigurationException, ServiceUnavailableException {
		return getChannelGroupInfo().get(channelGroupId);
	}

	/**
	 * Provides a map containing metadata of all channel groups and their channels<br>
	 * 
	 * 
	 * @return A HashMap identifying each group by it's id and providing the following information per group:
	 *         <ul>
	 *         <li><b>Display name</b> - The name of the channel group<br/>
	 *         <i>For the default group this value will always be "<b>Unassigned Channels</b>"</i></li>
	 *         <li><b>Id</b> - The UUID of the channel group.<br/>
	 *         <i>For the default group this value will always be "<b>Unassigned Channels</b>"</i></li>
	 *         <li><b>Description</b> - A description of the purpose of this channel group</li>
	 *         <li><b>Last modified</b> - Timestamp of the last modification</li>
	 *         <li><b>Display date</b> - A human readable representation of the <b>Last modified</b> timestamp in the format <b>dd.MM.yyyy,
	 *         HH:mm:ss</b></li>
	 *         <li><b>Version</b> - The version of the channel group. Every change increments the version number by 1.</li>
	 *         <li><b>Mirth version</b> - The version of the Mirth system for which the channel group has been deployed</li>
	 *         <li><b>Group</b> - indicates that it is a grouping element (value is always <b>true</b>)</li>
	 *         <li><b>Type</b> - <b>channelGroup</b></li>
	 *         <li><b>Members</b> - Lists all channels that belong to this channel group with the following attributes:
	 *         <ul>
	 *         <li><b>id</b> - The UUID of the channel</li>
	 *         <li><b>Display name</b> - The name of the channel</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>Number of members</b> - The number of channel that belong to this channel group</li>
	 *         <li><b>artificial</b> - is set to true, if it is the default group (which actually is not really a group) and false, otherwise</li>
	 *         </ul>
	 * @throws ServiceUnavailableException
	 * @throws ConfigurationException
	 */
	private HashMap<String, JSONObject> getChannelGroupInfo() throws ConfigurationException, ServiceUnavailableException {
		// lazy fetching
		if (this.channelGroupInfo == null) {

			HttpURLConnection service = null;
			// either code-template libraries or channel groups
			JSONObject raw = null;
			JSONObject currentGroup = null;
			JSONArray groups = null;
			String mirthVersion = null;

			HashSet<String> assignedChannels = new HashSet<String>();
			this.channelGroupInfo = new HashMap<String, JSONObject>();
			this.channelGroupOrder = new TreeMap<String, String>();

			// 1.) retrieve the group structure and harmonize it if necessary
			service = connectToRestService("/api/channelgroups");
			raw = getResponseAsJson(service);

			if (raw != null) {
				// make sure that it is always an array - even if only one channel group was provided
				groups = (raw.get(CHANNEL_GROUP) instanceof JSONArray) ? raw.getJSONArray(CHANNEL_GROUP)
						: new JSONArray().put(raw.getJSONObject(CHANNEL_GROUP));

				// 2.) fetch meta data for all groups

				// extract the version of the mirth system from the first channel
				if (mirthVersion == null) {
					mirthVersion = groups.getJSONObject(0).getString("version");
				}

				// collect meta data for all channel groups
				for (Object element : groups) {
					// scan next group
					currentGroup = (JSONObject) element;
					// a new jsonObject to put the groups main attributes
					JSONObject metaData = new JSONObject();

					// add the group name
					metaData.accumulate("Display name", currentGroup.getString("name"));
					// add the group id
					metaData.accumulate("Id", currentGroup.getString("id"));
					// indicate that this group is not artificial
					metaData.accumulate("artificial", false);
					// add the description of the channel purpose
					metaData.accumulate("Description", currentGroup.getString("description").replaceAll("&lt;", "<").replaceAll("&gt;", ">")
							.replaceAll("\\r\\n|\\r|\\n", "<br>"));
					// add the last modified date for sorting
					metaData.accumulate("Last modified", currentGroup.getJSONObject("lastModified").getLong("time"));
					// add the last modified date for displaying
					metaData.accumulate("Display date", formatDate(metaData.getLong("Last modified")));
					// add the revision id
					metaData.accumulate("Version", currentGroup.get("revision"));
					// add the revision id
					metaData.accumulate("Mirth version", mirthVersion);
					// indicate that it is a grouping element
					metaData.accumulate("Group", true);
					// not yet sure for what the item type is needed
					metaData.accumulate("Type", CHANNEL_GROUP);
					// 3.) Add ordered references to channels
					TreeMap<String, String> groupMemberOrder = new TreeMap<String, String>();
					// tag does not exist if group is empty
					try {
						// get harmonized reference to all channels of a group
						JSONArray groupMembers = (currentGroup.getJSONObject("channels").get("channel") instanceof JSONArray)
								? currentGroup.getJSONObject("channels").getJSONArray("channel")
								: new JSONArray().put(currentGroup.getJSONObject("channels").getJSONObject("channel"));

						// now order all channels of the channel group by name
						for (Object member : groupMembers) {
							// get the reference to the channel
							String reference = ((JSONObject) member).getString("id");
							// if the referenced channel actually exists
							if (getChannelInfo().containsKey(reference)) {
								// add it to the ordered map with its name as key
								groupMemberOrder.put(getChannelInfo().get(reference).getString("Display name").toLowerCase(), reference);
								// remember that this channel has been assigned to a group
								assignedChannels.add(reference);
							} else {
								logger.error("The channel group \""+currentGroup.getString("name")+"\" references a channel with id \""+reference+"\" that does not exist.");
							}
						}

						// add information about the number of channels in this group
						metaData.accumulate("Number of members", groupMemberOrder.size());
						// add the ordered list of references to the channel group meta data
						metaData.put("Members", groupMemberOrder.values());

					} catch (JSONException ex) {
						// this group does not possess any channels
						metaData.accumulate("Number of members", 0);
					}

					// 4.) add channel group to the ordered list
					this.channelGroupOrder.put(metaData.getString("Display name").toLowerCase(), metaData.getString("Id"));
					// and also to the cache
					this.channelGroupInfo.put(metaData.getString("Id"), metaData);
				}
			}

			// 5.) generate a default group and add all unassigned channels
			JSONObject metaData = new JSONObject();

			// add the group name
			metaData.accumulate("Display name", "Unassigned Channels");
			// add the group id
			metaData.accumulate("Id", "Unassigned Channels");
			// indicate that this group is artificial
			metaData.accumulate("artificial", true);
			// add the description of the channel purpose
			metaData.accumulate("Description", "All channels that have not yet been assigned to a group.");
			// add the last modified date for sorting
			metaData.accumulate("Last modified", null);
			// add the last modified date for displaying
			metaData.accumulate("Display date", "-");
			// add the revision id
			metaData.accumulate("Version", "1");
			// add the revision id
			metaData.accumulate("Mirth version", mirthVersion);
			// indicate that it is a grouping element
			metaData.accumulate("Group", true);
			// not yet sure for what the item type is needed
			metaData.accumulate("Type", CHANNEL_GROUP);

			// now scan for channels that have not been assigned to a channel group
			TreeMap<String, String> groupMemberOrder = new TreeMap<String, String>();
			for (String currentChannelId : getChannelInfo().keySet()) {
				if (assignedChannels.contains(currentChannelId)) {
					continue;
				}
				groupMemberOrder.put(getChannelInfo().get(currentChannelId).getString("Display name").toLowerCase(), currentChannelId);
			}
			// add information about the number of channels in this group
			metaData.accumulate("Number of members", groupMemberOrder.size());
			// add the ordered list of references to the "Unassigned channels" group meta data
			metaData.put("Members", groupMemberOrder.values());

			// add the artificial channel group to the ordered list
			this.channelGroupOrder.put(metaData.getString("Display name").toLowerCase(), metaData.getString("Id"));
			// and also to the cache
			this.channelGroupInfo.put(metaData.getString("Id"), metaData);

			// update the update indicator
			this.lastUpdate = System.currentTimeMillis();
		}

		return this.channelGroupInfo;
	}

	/**
	 * Provides the metadata of a specific channel identified by it's id
	 * 
	 * @param channelId
	 *            The unique channel id
	 * @return The channel meta data
	 * @throws ServiceUnavailableException
	 * @throws ConfigurationException
	 */
	private JSONObject getChannelMetaDataById(String channelId) throws ConfigurationException, ServiceUnavailableException {
		return getChannelInfo().get(channelId);
	}

	/**
	 * Provides meta data information of all code template libraries and assigned code templates
	 * 
	 * @return Meta data information of all code template libraries and assigned code templates
	 * @throws ServiceUnavailableException
	 */
	private JSONObject getCodeTemplateLibraryMetaData() throws ServiceUnavailableException {
		return getCodeTemplateLibraryMetaDataById(null);
	}

	/**
	 * Provides meta data information of a specific code template library and assigned code templates
	 * 
	 * @param id
	 *            The id of the desired code template library. If null, the meta data of all libraries will be provided
	 * @return Meta data information of a specific code template libraries and assigned code templates
	 * @throws ServiceUnavailableException
	 * @throws JSONException
	 */
	private JSONObject getCodeTemplateLibraryMetaDataById(String id) throws ServiceUnavailableException {
		JSONObject metaData = new JSONObject();

		// no specific id means all code template libraries
		if (id == null) {
			// get all code template libraries in alphabetical order
			for (String codeTemplateLibraryId : getCodeTemplateLibraryList()) {
				// get the metadata for the current code template library
				JSONObject currentGroup = getCodeTemplateLibraryInfoById(codeTemplateLibraryId);
				// and add it to the structure
				metaData.accumulate("item", currentGroup);

				// now add the metadata of all child elements
				if (currentGroup.has("Members")) {
					for (Object codeTemplateId : currentGroup.getJSONArray("Members")) {
						// add the current element to the structure
						metaData.accumulate("item", getCodeTemplateMetaDataById((String) codeTemplateId));
					}
				}
			}
			// add info about the total number of groups (the group of unassigned channels is not a real group)
			metaData.put("Number of groups", getCodeTemplateLibraryInfo().size());
			// add info about the total number of channels
			metaData.put("Number of members", getCodeTemplateInfo().size());
		} else {
			// get the metadata for the current code template library
			JSONObject currentGroup = getCodeTemplateLibraryInfoById(id);
			// and add it to the structure
			metaData.accumulate("item", currentGroup);

			// now add the metadata of all child elements
			if (currentGroup.has("Members")) {
				for (Object codeTemplateId : currentGroup.getJSONArray("Members")) {
					// add the current element to the structure
					metaData.accumulate("item", getCodeTemplateMetaDataById((String) codeTemplateId));
				}
			}

			// add info about the total number of groups, which is always 1 in this case
			metaData.put("Number of groups", 1);
			// add info about the total number of channels
			metaData.put("Number of members", currentGroup.getJSONArray("Members").length());
		}

		// if the is no channel from which it can be taken, fetch it from the server via the api (expensive!)
		metaData.put("Mirth version", getMirthVersion().getVersionString());

		// make sure that attribute item is always a JSON array
		if (metaData.has("item")) {
			if (metaData.get("item") instanceof JSONObject) {
				// transform the only item to an array
				JSONArray itemGroup = new JSONArray().put(metaData.get("item"));
				metaData.remove("item");
				metaData.put("item", itemGroup);
			}
		} else {
			// there are no items. Use an empty array
			metaData.put("item", new JSONArray());
		}

		return metaData;
	}

	/**
	 * Provides an ordered list of all code template libraries
	 * 
	 * @return A collection of code template library ids
	 * @throws ServiceUnavailableException
	 */
	private Collection<String> getCodeTemplateLibraryList() throws ServiceUnavailableException {
		if (this.codeTemplateLibraryInfo == null) {
			// it is important to first generate the information before they are provided to the user
			getCodeTemplateLibraryInfo();
		}

		return this.codeTemplateLibraryOrder.values();
	}

	/**
	 * Provides a mapping between functions and all channels that reference this function
	 * 
	 * @return The mapping between functions and the channels that reference these functions
	 * @throws ServiceUnavailableException
	 * @throws ConfigurationException
	 */
	private HashMap<String, TreeSet<String>> getChannelReferencesToFunction() throws ConfigurationException, ServiceUnavailableException {
		if (this.channelReferencesToFunction == null) {
			// load channel information as this also parses channels for function references
			getChannelInfo();
		}

		return this.channelReferencesToFunction;
	}

	/**
	 * Forces the Mirth client instance to reload the channel, code template, channel group, and code template library configuration from the server
	 * 
	 * @throws ServiceUnavailableException
	 */
	private void forceRefresh() throws ServiceUnavailableException {
		// empty the configuration of this instance
		this.channelCodeTemplateLibraryReferences = null;
		this.channelFunctionReferences = null;
		this.channelGroupInfo = null;
		this.channelGroupOrder = null;
		this.channelInfo = null;
		this.channelInternalFunctionsByChannelId = null;
		this.channelLastModified = null;
		this.channelReferencesToFunction = null;
		this.channelState = null;
		this.channelIdbyName = null;
		this.channelNameById = null;
		this.codeTemplateIdToFunction = null;
		this.codeTemplateLibraryIdByCodeTemplateId = null;
		this.codeTemplateInfo = null;
		this.codeTemplateLibraryInfo = null;
		this.codeTemplateLibraryOrder = null;
		this.codeTemplateIdbyName = null;
		this.codeTemplateNameById = null;
		this.functionLinkedByFunctions = null;
		this.codeTemplateIdByFunctionName = null;
		this.functionUsesFunctions = null;
		this.externalResources = null;
		this.interChannelDependencies = null;
		this.functionConflicts = null;
		this.unknownChannelFunctions = null;
		this.unknownFunctionFunctions = null;
	}

	/**
	 * Provides the metadata of a specific code template library identified by it's unique id.<br>
	 * This does <u><i>not</i></u> include the metadata of the contained code templates.<br>
	 * <br>
	 * This is is needed, the function {@link #getCodeTemplateLibraryMetaDataById(String)} should be called
	 * 
	 * @param codeTemplateLibraryId
	 *            The code template library id
	 * @return The code template library meta data
	 * @throws ServiceUnavailableException
	 */
	private JSONObject getCodeTemplateLibraryInfoById(String codeTemplateLibraryId) throws ServiceUnavailableException {
		return getCodeTemplateLibraryInfo().get(codeTemplateLibraryId);
	}

	/**
	 * Provides a map containing metadata of all code template libraries. If it does not exist, it will be generated.<br>
	 * <br>
	 * It consists of the following information:<br>
	 * <ul>
	 * <li><b>Name</b> - The name of the channel group</li>
	 * <li><b>Id</b> - The UUID of the channel group</li>
	 * <li><b>Description</b> - A description of the code template</li>
	 * <li><b>Version</b> - The version of the channel group. Every change increases version number by 1.</li>
	 * <li><b>Group</b> - Indicates that it is a grouping element</li>
	 * <li><b>Last modified</b> - Timestamp of the last modification of the code template library</li>
	 * <li><b>Display date</b> - A human readable representation of the <b>Last modified</b> timestamp</li>
	 * <li><b>Used by</b> - A list of ids of channels that reference this code template library</li>
	 * <li><b>Number of code templates</b> - The number of code templates the library comprises</li>
	 * <li><b>Code templates</b> - An ordered list of ids of code templates that belong to this code template library</li>
	 * </ul>
	 * 
	 * @return A hashmap with meta data of all code template libraries identified by their id
	 * @throws ServiceUnavailableException
	 */
	private HashMap<String, JSONObject> getCodeTemplateLibraryInfo() throws ServiceUnavailableException {

		// lazy fetching
		if (this.codeTemplateLibraryInfo == null) {
			HttpURLConnection service = null;
			// either code-template libraries or channel groups
			JSONObject raw = null;
			JSONObject currentGroup = null;
			JSONArray groups = null;

			// 1.) retrieve the group structure and harmonize it if necessary
			service = connectToRestService("/api/codeTemplateLibraries");
			raw = getResponseAsJson(service);

			this.codeTemplateLibraryInfo = new HashMap<String, JSONObject>();
			this.codeTemplateLibraryOrder = new TreeMap<String, String>();
			this.channelCodeTemplateLibraryReferences = new HashMap<String, ArrayList<String>>();
			this.codeTemplateLibraryIdByCodeTemplateId = new HashMap<String, String>();
			this.codeTemplateIdByFunctionName = new HashMap<String, String>();
			
			// if there are no code template libraries at the Mirth instance
			if (raw == null) {
				// no more work has to be done
				return this.codeTemplateLibraryInfo;
			}

			// if it is an object instead of an array (meaning only 1 code template library)
			groups = (raw.get(CODE_TEMPLATE_LIBRARY) instanceof JSONArray) ? raw.getJSONArray(CODE_TEMPLATE_LIBRARY)
					: new JSONArray().put(raw.getJSONObject(CODE_TEMPLATE_LIBRARY));

			// 2.) fetch meta data for all code template library

			// extract the version of the mirth system from the first channel
			String mirthVersion = getMirthVersion().getVersionString();

			// collect meta data for all code template libraries
			for (Object element : groups) {
				// scan next group
				currentGroup = (JSONObject) element;
				// a new jsonObject to put the groups main attributes
				JSONObject metaData = new JSONObject();

				// add the code template library name
				metaData.accumulate("Display name", currentGroup.getString("name"));
				// add the code template library id
				String libraryId = currentGroup.getString("id");
				metaData.accumulate("Id", libraryId);
				// add the description of the code template library purpose
				metaData.accumulate("Description", currentGroup.getString("description").replaceAll("&lt;", "<").replaceAll("&gt;", ">")
						.replaceAll("\\\"", "&quot;").replaceAll("\\r|\\n", "<br>"));
				// add the last modified date for sorting
				metaData.accumulate("Last modified", currentGroup.getJSONObject("lastModified").getLong("time"));
				// add the last modified date for displaying
				metaData.accumulate("Display date", formatDate(metaData.getLong("Last modified")));
				// add the revision id
				metaData.accumulate("Version", currentGroup.getInt("revision"));
				// add the revision id
				metaData.accumulate("Mirth version", mirthVersion);
				// indicate that it is a grouping element
				metaData.accumulate("Group", true);
				// not sure for what the item type is needed
				metaData.accumulate("Type", CODE_TEMPLATE_LIBRARY);

				// 3.) references to channels
				if (currentGroup.get("enabledChannelIds") instanceof JSONObject) {

					// get harmonized reference to all code templates of a code template library
					JSONArray referencingChannels = (currentGroup.getJSONObject("enabledChannelIds").get("string") instanceof JSONArray)
							? currentGroup.getJSONObject("enabledChannelIds").getJSONArray("string")
							: new JSONArray().put(currentGroup.getJSONObject("enabledChannelIds").getString("string"));

					// add the ordered list of referencing channels
					metaData.put("Used by", referencingChannels);

					// build up an index of libraries used per channel
					for (Object id : referencingChannels) {
						// get the next channel reference
						String channelId = (String) id;
						// if there is not yet an entry for the channel
						if (!this.channelCodeTemplateLibraryReferences.containsKey(channelId)) {
							// generate one
							this.channelCodeTemplateLibraryReferences.put(channelId, new ArrayList<String>(1));
						}

						// add library reference to the channel
						ArrayList<String> references = this.channelCodeTemplateLibraryReferences.get(channelId);
						references.add(libraryId);
					}
				}

				// 4.) Add ordered references to code templates
				TreeMap<String, String> groupMemberOrder = new TreeMap<String, String>();
				try {
					// get harmonized reference to all code templates of a code template library
					JSONArray groupMembers = (currentGroup.getJSONObject("codeTemplates").get("codeTemplate") instanceof JSONArray)
							? currentGroup.getJSONObject("codeTemplates").getJSONArray("codeTemplate")
							: new JSONArray().put(currentGroup.getJSONObject("codeTemplates").getJSONObject("codeTemplate"));

					// now order all templates of the library by name
					for (Object member : groupMembers) {

						// get the reference to the code template
						String codeTemplateId = ((JSONObject) member).getString("id");
						// and also the function name
						String functionName = getCodeTemplateMetaDataById(codeTemplateId).getString("Function name");
						// and add it to the ordered map with its name as key
						groupMemberOrder.put(functionName.toLowerCase(), codeTemplateId);
						// create mapping from function to library
						this.codeTemplateLibraryIdByCodeTemplateId.put(codeTemplateId, libraryId);					
					
						int counter = 2;
						String functionId = null;
						while (getCodeTemplateInfo().containsKey(codeTemplateId + "_" + counter)) {
							// create the function id
							functionId = codeTemplateId + "_" + counter++;
							// get the function name
							functionName = getCodeTemplateMetaDataById(functionId).getString("Function name");
							// add it to the order list
							groupMemberOrder.put(functionName.toLowerCase(), functionId);
							// create mapping from function to library
							this.codeTemplateLibraryIdByCodeTemplateId.put(functionId, libraryId);

							// and also one from function name to code template
							this.codeTemplateIdByFunctionName.put(functionName, codeTemplateId);
						}
					}

					// add information about the number of templates in this group
					metaData.accumulate("Number of members", groupMemberOrder.size());
					// add the ordered list of references to the code template library meta data
					metaData.put("Members", groupMemberOrder.values());
				} catch (JSONException ex) {
					// this group does not possess any code template
					metaData.accumulate("Number of members", 0);
				}

				// 5.) add code template library to the ordered list
				this.codeTemplateLibraryOrder.put(metaData.getString("Display name").toLowerCase(), metaData.getString("Id"));
				// and also to the cache
				this.codeTemplateLibraryInfo.put(libraryId, metaData);
			}
			// update the update indicator
			this.lastUpdate = System.currentTimeMillis();
		}


		return this.codeTemplateLibraryInfo;
	}
	
	/**
	 * Checks if there is a conflict for this function. The conflict itself can be obtained via getFunctionConflicts()
	 * @param functionName The name of the function that should be checked
	 * @param codeTemplateId The id of the code template against which the function should be checked
	 * @return True, if there is a conflict; false otherwise
	 * @throws JSONException
	 * @throws ServiceUnavailableException
	 */
	private boolean checkForFunctionConflicts(String functionName, String codeTemplateId) throws JSONException, ServiceUnavailableException {

		// if there is already a link to a code template for this function it means the function is defined multiple times
		if((this.codeTemplateIdByFunctionName!= null) && (this.codeTemplateIdByFunctionName.containsKey(functionName))) {
			// as javascript does not support function overloading by defining a function multiple times w/ differing parameter sets, we might have an issue here
			// first check if there is already a valid container for function conflicts
			if(this.functionConflicts == null) {
				// nope, create it
				this.functionConflicts = new HashMap<String, HashMap<String, Integer>>();
			}
			
			// check if there is not yet a conflict record for this function
			if(!this.functionConflicts.containsKey(functionName)) {
				// create a new entry
				HashMap<String, Integer> newEntry = new HashMap<String, Integer>();
				// add the initial code template
				newEntry.put(getCodeTemplateNameById(this.codeTemplateIdByFunctionName.get(functionName)), 1);
				// add the entry to the conflict list
				functionConflicts.put(functionName, newEntry);
			}
			
			// get the record for this function
			HashMap<String, Integer> conflictRecord = functionConflicts.get(functionName);
			// and also the name of the code template
			String codeTemplateName = getCodeTemplateNameById(codeTemplateId);
			
			// add a detection for this code template (a function might be defined multiple times on the same code template)
			conflictRecord.put(codeTemplateName, conflictRecord.containsKey(codeTemplateName) ? conflictRecord.get(codeTemplateName) + 1 : 1);
		    for (Map.Entry<String, Integer> entry : functionConflicts.get(functionName).entrySet()) {
	            String codeTemplateNameReference = entry.getKey();
	            Integer amount = entry.getValue();
	        }
			return true;
		}
		
		return false;
	}
	
	/**
	 * Provides the conflicts of a function
	 * @param functionName The name of the function
	 * @return a HashMap containing the conflicting code templates and the number of times the function is defined in the respective code template
	 * @throws ServiceUnavailableException
	 */
	private HashMap <String, Integer> getFunctionConflicts(String functionName) throws ServiceUnavailableException{

		return (this.functionConflicts != null) ? this.functionConflicts.get(functionName) : null;
	}

	/**
	 * Provides the metadata of a specific code template identified by it's id
	 * 
	 * @param codeTemplateId
	 *            The unique code template id
	 * @return The code template meta data
	 * @throws ServiceUnavailableException
	 */
	private JSONObject getCodeTemplateMetaDataById(String codeTemplateId) throws ServiceUnavailableException {
		return getCodeTemplateInfo().get(codeTemplateId);
	}

	/**
	 * Provides a map containing metadata of all code templates. If it does not exist, it will be generated.
	 * 
	 * @return A HashMap with meta data of all code templates identified by their id. The value consists of a JSON object containing the following
	 *         information:<br>
	 *         <ul>
	 *         <li><b>Display name</b> - The display name that has been configured for the code template</li>
	 *         <li><b>Function name</b> - The name of the actual function if the code template is a function. Otherwise the same like <b>Display
	 *         name</b></li>
	 *         <li><b>Id</b> - The UUID of the code template</li>
	 *         <li><b>Version</b> - The version of the code template. Every change increases version number by 1.</li>
	 *         <li><b>Last modified</b> - The timestamp of the last modification of the code template</li>
	 *         <li><b>Display date</b> - A human readable representation of the <b>Last modified</b> timestamp</li>
	 *         <li><b>Description</b> - A description of the code template</li>
	 *         <li><b>Parameters</b> - A description of the parameters</li>
	 *         <li><b>Return value</b> - A description of the return value</li>
	 *         <li><b>Is function</b> - True, if code template is a function</li>
	 *         </ul>
	 * @throws ServiceUnavailableException
	 */
	private synchronized HashMap<String, JSONObject> getCodeTemplateInfo() throws ServiceUnavailableException {
		// lazy fetching
		if (this.codeTemplateInfo == null) {
			// initialize container
			this.codeTemplateInfo = new HashMap<String, JSONObject>();
			// and also the name to template mapper
			this.codeTemplateIdbyName = new HashMap<String, String>();
			this.codeTemplateNameById = new HashMap<String, String>();
			this.codeTemplateIdToFunction = new HashMap<String, HashSet<String>>();

			// get info about all code templates
			String xml = getResponseAsXml(connectToRestService("/api/codeTemplates"));
			// scan the channel code for code template usage
			buildUpTemplateToTemplateRelationships(xml);
			// and prepare it for metadata parsing
			JSONObject raw = XML.toJSONObject(xml);
			try {
				raw = raw.getJSONObject("list");
			} catch (JSONException e) {
				// if the library is empty, there will be no code template information
				return this.codeTemplateInfo;
			}

			// and extract the relevant information of each
			JSONArray codeTemplates = (raw.get("codeTemplate") instanceof JSONArray) ? raw.getJSONArray("codeTemplate") : (new JSONArray()).put(raw.get("codeTemplate"));
			for (Object element : codeTemplates) {
				// get next code template
				JSONObject codeTemplate = (JSONObject) element;

				// arm the matcher to check for function definitions within the code template
				Matcher functionNameMatcher = functionNamePattern.matcher(codeTemplate.getJSONObject("properties").getString("code"));
				// if a function definition is found, the code template contains at least one function
				if (functionNameMatcher.find()) {
					String codeTemplateId = codeTemplate.getString("id");
					String functionName = functionNameMatcher.group(1) + "()";

					// initialize the cache entry
					this.codeTemplateIdToFunction.put(codeTemplateId, new HashSet<String>());
					// add metadata for the first function of the code template
					generateCodeTemplateMetaData(codeTemplate, functionName);
					// cache the reference between code template and function
					this.codeTemplateIdToFunction.get(codeTemplateId).add(functionName);
					
					// add metadata for each function within the code template
					int index = 2;
					while (functionNameMatcher.find()) {
						functionName = functionNameMatcher.group(1) + "()";
						// add metadata for the function
						generateCodeTemplateMetaData(codeTemplate, functionName, index++);
						// cache the reference between code template and function
						this.codeTemplateIdToFunction.get(codeTemplateId).add(functionName);
					}
				} else {
					// code template does not contain any function definitions. Create meta data anyway
					generateCodeTemplateMetaData(codeTemplate);
				}
			}
			// update the update indicator
			this.lastUpdate = System.currentTimeMillis();
		}

		return this.codeTemplateInfo;
	}
	
	/**
	 * Provides a list of inter-function-references
	 * 
	 * @return A list of functions referenced by functions
	 * @throws ServiceUnavailableException
	 */
	private HashMap<String, ArrayList<String>> getFunctionUsesFunctions() throws ServiceUnavailableException {
		if (this.functionUsesFunctions == null) {
			getCodeTemplateInfo();
		}

		return this.functionUsesFunctions;
	}


	/**
	 * Generates metadata for a code template that is no function. It consists of the following information:<br>
	 * <ul>
	 * <li><b>Display name</b><br>
	 * <i>The display name that has been configured for the code template</i></li>
	 * <li><b>Function name</b><br>
	 * <i>The same like <b>Display name</b> as this code template is no function</i></li>
	 * <li><b>Id</b><br>
	 * <i>The UUID of the code template</i></li>
	 * <li><b>Version</b><br>
	 * <i>The version of the code template. Every change increases version number by 1.</i></li>
	 * <li><b>Last modified</b><br>
	 * <i>Timestamp of the last modification of the code template</i></li>
	 * <li><b>Display date</b><br>
	 * <i>A human readable representation of the <b>Last modified</b> timestamp</i></li>
	 * <li><b>Description</b><br>
	 * <i>A description of the code template</i></li>
	 * <li><b>Is function</b><br>
	 * <i>always false, as this code template is no function</i></li>
	 * </ul>
	 * 
	 * @param codeTemplate
	 *            The code template for which the metadata should be generated
	 * @throws ServiceUnavailableException
	 * 
	 */
	private void generateCodeTemplateMetaData(JSONObject codeTemplate) throws ServiceUnavailableException {
		generateCodeTemplateMetaData(codeTemplate, null, null);
	}

	/**
	 * Generates metadata for a code template. It consists of the following information:
	 * <ul>
	 * <li><b>Display name</b> - The display name that has been configured for the code template</li>
	 * <li><b>Function name</b> - The name of the actual function if the code template is a function. Otherwise the same like <b>Display name</b></li>
	 * <li><b>Id</b> - The UUID of the code template</li>
	 * <li><b>Version</b> - The version of the code template. Every change increases version number by 1.</li>
	 * <li><b>Last modified</b> - Timestamp of the last modification of the code template</li>
	 * <li><b>Display date</b> - A human readable representation of the <b>Last modified</b> timestamp</li>
	 * <li><b>Description</b> - A description of the code template</li>
	 * <li><b>Parameters</b> - A description of the parameters</li>
	 * <li><b>Return value</b> - A description of the return value</li>
	 * <li><b>Is function</b> - True, if code template is a function</li>
	 * </ul>
	 * 
	 * @param codeTemplate
	 *            The code template for which the metadata should be generated
	 * @param functionName
	 *            The name of the function or null if it is no function
	 * @throws ServiceUnavailableException
	 */
	private void generateCodeTemplateMetaData(JSONObject codeTemplate, String functionName) throws ServiceUnavailableException {
		generateCodeTemplateMetaData(codeTemplate, functionName, null);
	}

	/**
	 * Generates metadata for a code template. It consists of the following information:
	 * <ul>
	 * <li><b>Display name</b> - The display name that has been configured for the code template</li>
	 * <li><b>Function name</b> - The name of the actual function if the code template is a function. Otherwise the same like <b>Display name</b></li>
	 * <li><b>Id</b> - The UUID of the code template</li>
	 * <li><b>Version</b> - The version of the code template. Every change increases version number by 1.</li>
	 * <li><b>Last modified</b> - Timestamp of the last modification of the code template</li>
	 * <li><b>Display date</b> - A human readable representation of the <b>Last modified</b> timestamp</li>
	 * <li><b>Description</b> - A description of the code template</li>
	 * <li><b>Parameters</b> - A description of the parameters</li>
	 * <li><b>Return value</b> - A description of the return value</li>
	 * <li><b>Is function</b> - True, if code template is a function</li>
	 * </ul>
	 * 
	 * @param codeTemplate
	 *            The code template for which the metadata should be generated
	 * @param functionName
	 *            The name of the function or null if it is no function
	 * @param index
	 *            An index that will be added to the code template id if there are more than 1 function in a code template. If index is null, none
	 *            will be added
	 * @throws ServiceUnavailableException
	 */
	private void generateCodeTemplateMetaData(JSONObject codeTemplate, String functionName, Integer index) throws ServiceUnavailableException {
		// create a new element
		JSONObject metaData = new JSONObject();
		// add the configured name of the code template
		metaData.accumulate("Display name", codeTemplate.getString("name"));
		// add the function name of the code template. If it is no function use the template name
		metaData.accumulate("Function name", (functionName != null) ? functionName : metaData.getString("Display name"));
		// add the id of the code template. If there are more than one functions in a code template, create an artificial id (that allows to
		// reconstruct the original id)
		String codeTemplateId = codeTemplate.getString("id") + ((index != null) ? "_" + index.intValue() : "");
		metaData.accumulate("Id", codeTemplateId);
		// add the version of the code template
		metaData.accumulate("Version", codeTemplate.get("revision"));
		// not yet sure for what the item type is needed
		metaData.accumulate("Type", CODE_TEMPLATE);

		if (codeTemplate.getJSONObject("lastModified") != null) {
			// add the last modified date for sorting
			metaData.accumulate("Last modified", codeTemplate.getJSONObject("lastModified").get("time"));
			// add the last modified date for displaying
			metaData.accumulate("Display date", formatDate(metaData.getLong("Last modified")));
		} else {
			// no modified date
			metaData.accumulate("Last modified", "-");
			// no modified date
			metaData.accumulate("Display date", "-");
		}

		JSONObject header = getCodeTemplateDescription(codeTemplate, functionName);

		// if there is a JavaScript Doc header for this function, add the available details
		if (header != null) {
			// if there is a description of the function
			if (header.has("description")) {
				// add the description of the code template
				metaData.accumulate("Description", header.getString("description"));
			}
			// if the function possesses parameters
			if (header.has("parameters")) {
				// add the parameters of the code template
				metaData.accumulate("Parameters", header.getString("parameters"));
			}
			// if the function provides a return value
			if (header.has("returnValue")) {
				// add the return value of the code template
				metaData.accumulate("Return value", header.getString("returnValue"));
			}
		}
		boolean isFunction = (functionName != null);
		// indicate if code template is contains functions
		metaData.accumulate("Is function", isFunction);

		// add the mapping to the metadata HashMap
		this.codeTemplateInfo.put(codeTemplateId, metaData);
		
		// as well as the mapping between the template name and the id
		getCodeTemplateIdByName().put(metaData.getString("Display name"), codeTemplateId);
		getCodeTemplateNameById().put(codeTemplateId, metaData.getString("Display name"));

		// if it is a function
		if (isFunction) {
			// also add a mapping between function name and code template id
			getCodeTemplateIdByName().put(metaData.getString("Function name"), codeTemplateId);

			// check if the function has conflicts
			if (checkForFunctionConflicts(functionName, codeTemplateId)) {
				// it has. Get the list of conflicts for this function 
				HashMap<String, Integer> allConflicts = getFunctionConflicts(functionName);

				// and assemble the display list of conflicting elements
				ArrayList<String> multipleDefinitions = new ArrayList<String>();
				for (Map.Entry<String, Integer> entry : allConflicts.entrySet()) {
					String conflictingCodeTemplate = entry.getKey();
					Integer numberOfDefinitions = entry.getValue();
					multipleDefinitions.add(conflictingCodeTemplate + ((numberOfDefinitions != 1) ? " (<b>" + numberOfDefinitions + "x</b>)" : ""));
				}

					// first check if there is already an issue attribute for this channel
					if (!metaData.has("Issues")) {
						// if not, create it
						metaData.put("Issues", new JSONObject());
					}
					// add the list of multiple definitions of the same function
					metaData.getJSONObject("Issues").put("multipleDefinitions", multipleDefinitions);
			}
		}
		
		// and also one from function name to code template
		this.codeTemplateIdByFunctionName.put(functionName, codeTemplateId);
	}

	/**
	 * Provides a HashMap containing the header information of all functions detected in a code template
	 * 
	 * @param codeTemplate
	 *            The codeTemplate from which the JavaScript Doc headers should be extracted
	 * @param functionName
	 *            The function for which the description headers should be extracted. If none is provided, the first JavaScript Doc header will be
	 *            used (this is e.g. the case if the code template does not contain a function) (<i>OPTIONAL</i>)
	 * @return A JSON object containing the following attributes:
	 *         <ul>
	 *         <li><b>description</b> - the description of the function or code template (<i>OPTIONAL</i>)</li>
	 *         <li><b>parameters</b> - the name and description of the function parameters (<i>OPTIONAL</i>)</li>
	 *         <li><b>returnValue</b> - the description of the return value of the function (<i>OPTIONAL</i>)</li>
	 *         </ul>
	 *         The return value is <b>null</b> if the required description was not found
	 */
	private JSONObject getCodeTemplateDescription(JSONObject codeTemplate, String functionName) {
		JSONObject properties = null;
		String code, javascriptDocHeader, parameters, parameterName, parameterDescription, parameterList;
		Matcher headerMatcher, codeTemplateHeaderDescriptionMatcher, codeTemplateHeaderParameterMatcher, codeTemplateHeaderReturnValueMatcher,
				functionParameterMatcher;

		// depending on the mirth version
		if (!codeTemplate.has("code")) {
			// the code section is encapsulated into properties. Thus make sure the right spot is accessed
			codeTemplate = codeTemplate.getJSONObject("properties");
		}
		// obtain the code block (which contains the header(s))
		code = codeTemplate.getString("code");
		// determine if the description for a specific function is wanted
		boolean isFunction = (functionName != null);

		// determine the right matcher depending on if the code template contains a function
		headerMatcher = isFunction ? functionHeaderPattern.matcher(code) : codeTemplateHeaderPattern.matcher(code);
		// now find the correct header
		while (headerMatcher.find()) {
			// make sure that the right function documentation is read
			if (isFunction && !functionName.equals(headerMatcher.group(2) + "()")) {
				continue;
			}

			properties = new JSONObject();
			// now analyze the JavaScript Doc header
			javascriptDocHeader = headerMatcher.group(1);

			if (javascriptDocHeader == null) {
				javascriptDocHeader = "";
			}
			// extract the function description
			codeTemplateHeaderDescriptionMatcher = codeTemplateHeaderDescriptionPattern.matcher(javascriptDocHeader);
			if (codeTemplateHeaderDescriptionMatcher.find()) {
				// and add it to the record
				properties.put("description", codeTemplateHeaderDescriptionMatcher.group(1).replaceAll("\\s*(\\r?\\n|\\r)", "\n")
						.replaceAll("(?i)<br?\\/>(\\r?\\n|\\r)", "\n").replaceAll("(?i)(?<!<(ol|ul|\\/ol|\\/ul|\\/li)>)(\\n)", "<br/>\n"));
			}

			// if the code template contains no functions
			if (!isFunction) {
				// analysis ends here
				return properties;
			}

			// extract the parameters
			parameters = "";
			parameterList = headerMatcher.group(3);
			// stores the detected description
			HashMap<String, String> detectedDescription = new HashMap<String, String>();

			codeTemplateHeaderParameterMatcher = codeTemplateHeaderParameterPattern.matcher(javascriptDocHeader);
			// detect all parameter descriptions in the function header
			while (codeTemplateHeaderParameterMatcher.find()) {
				// extract the parameter name
				parameterName = codeTemplateHeaderParameterMatcher.group(1).trim();
				// the parameter description
				parameterDescription = codeTemplateHeaderParameterMatcher.group(2).trim();
				// remove a potential "-" prefix
				if (parameterDescription.charAt(0) == '-') {
					parameterDescription = parameterDescription.substring(1);
				}
				// adjust description
				parameterDescription = parameterDescription.replaceAll("\\s*(\\r?\\n|\\r)", "\n").replaceAll("(?i)<br?\\/>(\\r?\\n|\\r)", "\n")
						.replaceAll("(?i)(?<!<(ol|ul|tr|td|table\\/ol|\\/ul|\\/li|\\/td|\\/tr|\\/table)>)(\\n)", "<br/>\n");
				// and add it to the list of detected descriptions
				detectedDescription.put(parameterName, parameterDescription);
			}

			// generate documentation for all function parameters
			functionParameterMatcher = functionParameterPattern.matcher(parameterList);
			while (functionParameterMatcher.find()) {
				// extract the parameter name
				String name = functionParameterMatcher.group();
				// the parameter description
				String description = "";
				// if there is a description for this parameter in the function header
				if (detectedDescription.containsKey(name)) {
					// load it from the list of detected descriptions
					description = detectedDescription.get(name);
				}
				// and add the formatted parameter
				parameters += String.format("<tr><td><b>%s</b>\t</td><td>%s</td></tr>\n", name, description);
			}
			if (!parameters.isEmpty()) {
				parameters = "<table class='parameters'>" + parameters + "</table>";
				// add the formatted parameter list to the result set
				properties.put("parameters", parameters);
			}

			// finally extract the return value description
			codeTemplateHeaderReturnValueMatcher = codeTemplateHeaderReturnValuePattern.matcher(javascriptDocHeader);
			if (codeTemplateHeaderReturnValueMatcher.find()) {
				// add the return value description
				properties.put("returnValue", codeTemplateHeaderReturnValueMatcher.group(1).replaceAll("\\s*(\\r?\\n|\\r)", "\n")
						.replaceAll("(?i)<br?\\/>(\\r?\\n|\\r)", "\n").replaceAll("(?i)(?<!<(ol|ul|\\/ol|\\/ul|\\/li)>)(\\n)", "<br/>\n"));
			}

			// header was found - not need for further investigation
			break;
		}

		return properties;
	}

	/**
	 * Provides the name of a code template that corresponds to a given id
	 *
	 * @param codeTemplateId
	 *            The id of the code template for which the name should be obtained
	 * @return name of the code template or null if no code template could be found that corresponds to the given id
	 * @throws ServiceUnavailableException
	 */
	private String getCodeTemplateNameById(String codeTemplateId) throws ServiceUnavailableException {
		return getCodeTemplateNameById().get(codeTemplateId);
	}

	/**
	 * Provides a map that allows to obtain a code template name by it's id
	 *
	 * @return The mapping
	 * @throws ServiceUnavailableException
	 */
	private  HashMap<String, String> getCodeTemplateNameById() throws ServiceUnavailableException {
		// if the mapping was not yet created
		if (this.codeTemplateNameById == null) {
			// make sure it exists before returning the reference
			getCodeTemplateInfo();
		}

		return this.codeTemplateNameById;
	}

	/**
	 * Provides the name of a code template that corresponds to a given id
	 *
	 * @param codeTemplateId
	 *            The id of the code template for which the id should be obtained
	 * @return id of the code template or null if no code template could be found that corresponds to the given name
	 * @throws ServiceUnavailableException
	 */
	private String getCodeTemplateIdByName(String codeTemplateId) throws ServiceUnavailableException {
		return getCodeTemplateIdByName().get(codeTemplateId);
	}
	
	/**
	 * Provides a map that allows to obtain a code template id by it's name
	 *
	 * @return The mapping
	 * @throws ServiceUnavailableException
	 */
	private HashMap<String, String> getCodeTemplateIdByName() throws ServiceUnavailableException {
		// if the mapping was not yet created
		if (this.codeTemplateIdbyName == null) {
			// make sure it exists before returning the reference
			getCodeTemplateInfo();
		}

		return this.codeTemplateIdbyName;
	}
	
	/**
	 * Provides the id of a channel that corresponds to a given name
	 *
	 * @param channelId
	 *            The id of the channel for which the id should be obtained
	 * @return id of the channel or null if no channel could be found that corresponds to the given name
	 * @throws ServiceUnavailableException
	 * @throws ConfigurationException 
	 */
	private String getChannelNameById(String channelId) throws ServiceUnavailableException, ConfigurationException {

		return getChannelNameById().get(channelId);
	}
	
	/**
	 * Maps channel ids to their name
	 *
	 * @return The mappings
	 * @throws ServiceUnavailableException
	 * @throws ConfigurationException 
	 */
	private HashMap<String, String> getChannelNameById() throws ServiceUnavailableException, ConfigurationException {
		// if the mapping was not yet created
		if (this.channelNameById == null) {
			// make sure it exists before returning the reference
			getChannelInfo();
		}

		return this.channelNameById;
	}
	
	/**
	 * Provides the id of a channel that corresponds to a given name
	 *
	 * @param channelName
	 *            The name of the channel for which the id should be obtained
	 * @return id of the channel or null if no channel could be found that corresponds to the given name
	 * @throws ServiceUnavailableException
	 * @throws ConfigurationException 
	 */
	private String getChannelIdByName(String channelName) throws ServiceUnavailableException, ConfigurationException {

		return getChannelIdByName().get(channelName);
	}

	
	/**
	 * Maps channel names to their channel id
	 *
	 * @return The map containing the mappings
	 * @throws ServiceUnavailableException
	 * @throws ConfigurationException 
	 */
	private HashMap<String, String> getChannelIdByName() throws ServiceUnavailableException, ConfigurationException {
		// if the mapping was not yet created
		if (this.channelIdbyName == null) {
			// make sure it exists before returning the reference
			getChannelInfo();
		}

		return this.channelIdbyName;
	}

	/**
	 * Detects the functions that are used by the code templates by parsing the code template source code. It caches a mapping between the referencing
	 * and referenced functions as well as the other way round.
	 * 
	 * @param xml
	 *            The source code of the code templates (code template definition)
	 */
	private void buildUpTemplateToTemplateRelationships(String xml) {

		String codeTemplateDefinition = null;
		int functionNameBegin, functionNameEnd;
		Matcher codeTemplateMatcher, nameMatcher, functionReferenceMatcher;

		this.functionLinkedByFunctions = new HashMap<String, TreeSet<String>>();
		this.functionUsesFunctions = new HashMap<String, ArrayList<String>>();

		codeTemplateMatcher = codeTemplatePattern.matcher(xml);
		while (codeTemplateMatcher.find()) {
			// extract the code template definition
			codeTemplateDefinition = codeTemplateMatcher.group();
			// if no function name will be found, the code template does not contain a function but might anyway have code that references a custom
			// function.
			// thus, in this case, use the code template name, instead - TODO: Maybe this should be replaced by code template id as name is not
			// guaranteed to be unique
			String currentFunctionName = null;

			int functionStart, functionEnd = -1;
			String functionBody = null;
			TreeSet<String> detectedFunctions = null;

			// now try to extract the function name
			Matcher functionNameMatcher = functionNamePattern.matcher(codeTemplateDefinition);
			if (functionNameMatcher.find()) {
				// there is a function definition in this code template
				functionStart = functionNameMatcher.end();
				// extract the first function name
				currentFunctionName = functionNameMatcher.group(1) + "()";
			} else {
				functionStart = codeTemplateDefinition.indexOf("<code>");
				// this code template does not contain any function definitions but might contain references.
				nameMatcher = namePattern.matcher(xml);
				// Thus use template name as name
				currentFunctionName = nameMatcher.find() ? nameMatcher.group(1) : "Unknown_" + UUID.randomUUID();
			}

			// scan all functions within a code template (as JavaScript is no regular language, pure regex can't be used for function body extraction)
			while (functionStart != -1) {
				// get end index of first function definition - which is the whole code if no further function is found
				functionEnd = functionNameMatcher.find() ? functionNameMatcher.end() : codeTemplateDefinition.length();

				// extract the function definition
				functionBody = codeTemplateDefinition.substring(functionStart, functionEnd);
				// prepare the function body for function detection
				functionBody = prepareForFunctionParsing(functionBody);
				// create a container for the detected functions
				detectedFunctions = new TreeSet<String>();

				// needed to avoid detecting regular expressions as false positives
				Matcher roughRegexMatcher = roughRegexDetectionPattern.matcher(functionBody);	
				
				// now scan for functions
				functionReferenceMatcher = functionReferenceDetectionPattern.matcher(functionBody);
				
				// and add all detected functions to a distinct list
				while (functionReferenceMatcher.find()) {
					// get the name of the referenced function
					String referencedFunctionName = functionReferenceMatcher.group(1);
					// if the current detected function is part of the filter list
					if (functionFilter.contains(referencedFunctionName)) {
						// it's a false positive - omit it
						continue;
					}
					
					// if this reference was alread detected
					if(detectedFunctions.contains(referencedFunctionName)) {
						// no need to scan it again
						continue;
					}
					
					// determine the location of the function reference w/i the code
					functionNameBegin = functionReferenceMatcher.start(1);
					functionNameEnd = functionReferenceMatcher.end(1);

					boolean isRegex = false;
					// start the regex search over from the begin of the source code
					roughRegexMatcher.reset();
					
					// check all regular expressions
					while (roughRegexMatcher.find()) {
						// Check if this function call is inside a regular expression
						if (functionNameBegin > roughRegexMatcher.start() && functionNameEnd < roughRegexMatcher.end()) {
							// Oops this was a regex
							isRegex = true;
							break;
						}
						// if the regex already starts after the function name
						if (functionNameEnd < roughRegexMatcher.start()) {
							// no further false positive scanning for this function is needed
							break;
						}
					}
					
					// if function reference was detected as regex
					if(isRegex) {
						// ignore it
						continue;
					}
					
					// it's no function w/o brackets ;-)
					referencedFunctionName += "()";
					// add the function to the result set (add this function/code template to the list of functions that use the currently detected
					// function)
					detectedFunctions.add(referencedFunctionName);
					// if the detected function was not yet referenced
					if (!this.functionLinkedByFunctions.containsKey(referencedFunctionName)) {
						// create a new entry for it
						this.functionLinkedByFunctions.put(referencedFunctionName, new TreeSet<String>());
					}
					// add the current function/code template to the list of functions that reference the function for which the reference was
					// detected
					this.functionLinkedByFunctions.get(referencedFunctionName).add(currentFunctionName);
				}

				if (detectedFunctions.size() > 0) {
					// add an entry for the function
					this.functionUsesFunctions.put(currentFunctionName, new ArrayList<String>(detectedFunctions));
				}

				// get start index of next function definition
				functionStart = functionNameMatcher.find() ? functionNameMatcher.end() : -1;
			}
		}
	}

	/**
	 * Provides a map containing metadata of all channels. If it does not exist, it will be generated.
	 * 
	 * @return A HashMap identifying each channel by it's id and providing the following information per channel:
	 *         <ul>
	 *         <li><b>Display name</b> - The name of the channel</li>
	 *         <li><b>Id</b> - The UUID of the channel</li>
	 *         <li><b>Description</b> - A description of the purpose of this channel</li>
	 *         <li><b>Last modified</b> - Timestamp of the last modification</li>
	 *         <li><b>Display date</b> - A human readable representation of the <b>Last modified</b> timestamp in the format <b>dd.MM.yyyy,
	 *         HH:mm:ss</b></li>
	 *         <li><b>Version</b> - The version of the channel. Every change increments the version number by 1.</li>
	 *         <li><b>Type</b> - <b>channel</b></li>
	 *         <li><b>Is disabled</b> - true, if the channel is disabled,not set if channel is enabled (<i>OPTIONAL</i>)</li>
	 *         </ul>
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 */
	private HashMap<String, JSONObject> getChannelInfo() throws ConfigurationException, ServiceUnavailableException {

		// lazy fetching
		if (this.channelInfo == null) {
			// initialize container
			HashMap<String, JSONObject> channelInfo = new HashMap<String, JSONObject>();
			this.channelFunctionReferences = new HashMap<String, ArrayList<String>>();
			this.channelReferencesToFunction = new HashMap<String, TreeSet<String>>();
			this.channelInternalFunctionsByChannelId = new HashMap<String, ArrayList<String>>();
			this.channelIdbyName = new HashMap<String, String>();
			this.channelNameById = new HashMap<String, String>();
			
			// get info about all channels. The pure xml is used for finding channel/code template relationships
			String xml = getResponseAsXml(connectToRestService("/api/channels"));
			// scan the channel code for code template usage
			buildUpCodeTemplateRelationships(xml);
			// and prepare it for metadata parsing
			JSONObject raw = XML.toJSONObject(xml);
			try {
				raw = raw.getJSONObject("list");
			} catch (JSONException e) {
				// if the channel group is empty, there will be no channel information
				this.channelInfo = channelInfo;
				return this.channelInfo;
			}

			// try to load caches
			HashMap<String, Long> channelLastModified = getChannelLastModified(false);
			HashMap<String, Boolean> channeState = getChannelState(false);

			// assure that an array will be used
			JSONArray channels = (raw.get("channel") instanceof JSONArray) ? raw.getJSONArray("channel") : (new JSONArray()).put(raw.get("channel"));

			// and extract the relevant information of each channel
			for (Object element : channels) {
				// get next channel
				JSONObject channel = (JSONObject) element;
				// create a new element
				JSONObject metaData = new JSONObject();
				// add the display name of the channel
				String channelName = channel.getString("name");
				metaData.accumulate("Display name", channelName);
				// add the id of the channel
				String channelId = channel.getString("id");
				metaData.accumulate("Id", channelId);
				// add the channel to the name to id mapping
				getChannelIdByName().put(channelName, channelId);
				// add the channel to the id to name mapping
				getChannelNameById().put(channelId, channelName);
				// add the version of the channel
				metaData.accumulate("Version", channel.get("revision"));
				// not yet sure for what the item type is needed
				metaData.accumulate("Type", CHANNEL);

				// add the last modified date for sorting (structure changed w/ Mirth version 3.6.0)
				Long lastModified = null;
				// and also the indicator if the channel is enabled
				boolean channelDisabled = false;

				// Now try to determine the last modified date of the channel
				if (channelLastModified.containsKey(channelId)) {
					// if the latest api version is supported, it can be taken from the cache
					lastModified = channelLastModified.get(channelId);
					// as well as the channel state
					channelDisabled = !channeState.get(channelId);
				} else if (channel.has("lastModified")) {
					// if channel uses the old format, the information is provided directly in the channel structure (to where it belongs from my
					// point of view)
					lastModified = channel.getJSONObject("lastModified").getLong("time");
					channelDisabled = !channel.getBoolean("enabled");
				} else {
					// as a last resort try location of newer format (structure changed w/ Mirth version 3.6.0)
					try {
						// unfortunately, the query method does not work like advertised. Thus try/catch is needed
						JSONObject infoLocation = (JSONObject) channel.query("/exportData/metadata");
						// let's do it brute force as it is caught anyway
						lastModified = infoLocation.getJSONObject("lastModified").getLong("time");
						channelDisabled = !infoLocation.getBoolean("enabled");
					} catch (Exception e) {
						// root.error("Last Modified - Exception!");
					}
				}
				// add the last modified date in ms
				metaData.accumulate("Last modified", lastModified);
				// add the last modified date for displaying
				metaData.accumulate("Display date", (lastModified != null) ? formatDate(lastModified) : "-");

				String description = channel.getString("description").replaceAll("&lt;", "<").replaceAll("&gt;", ">");
				// extract all documented channel changes and sort it from newest to oldest
				TreeMap<Long, String> changes = new TreeMap<Long, String>(Collections.reverseOrder());
				Matcher changesMatcher = changesPattern.matcher(description);
				while (changesMatcher.find()) {
					String changeDate, changeDescription;
					Date parsedDate = null;
					try {
						parsedDate = changeParseDateFormat.parse(changesMatcher.group(1));
						// format the data of the current change
						changeDate = changeDisplayDateFormat.format(parsedDate);

					} catch (java.text.ParseException e) {
						// if the date string format is invalid, keep it in the initial format
						changeDate = changesMatcher.group(1);
						// there is no date for ordering. Thus use now
						parsedDate = new Date(System.currentTimeMillis());
						// but log an error
						logger.error("The change date \"" + (changesMatcher.group(1) + "\" has an invalid format. It must be in the format yyyyDDmm!"));
					}
					// and also extract the change description
					changeDescription = changesMatcher.group(2).trim();
					changes.put(parsedDate.getTime(), "<tr><td><b>" + changeDate + "</b>\t</td><td>" + changeDescription + "</td></tr>");
				}

				// if changes were found
				if (changes.size() > 0) {
					// add changes as an attribute
					metaData.accumulate("Changes",
							"<table class='parameters'>" + changes.values().stream().collect(Collectors.joining("\n")) + "</table>");
					// and remove the change entries from the description
					description = changesMatcher.replaceAll("");
					// and also a version heading
					description = description.replaceFirst("(?i)[\\s\\#]*versions?\\s*\\:?[\\s\\#]*(?:\\r?:\\n|\\n)", "");
				}

				// now check for inbound and outbound interfaces to external systems
				TreeMap<String, String> inboundInterfaces = new TreeMap<String, String>();
				TreeMap<String, String> outboundInterfaces = new TreeMap<String, String>();

				Matcher systemInterfaceMatcher = systemInterfacePattern.matcher(description);
				while (systemInterfaceMatcher.find()) {
					String direction, externalSystem, dataType, connector;
					// INbound or OUTbound
					direction = systemInterfaceMatcher.group(1);
					// the connector id of the mirth channel
					connector = systemInterfaceMatcher.group(2);
					// the transferred data type
					dataType = systemInterfaceMatcher.group(3).replaceFirst("\\_.+$", "");
					// The name of the external system
					externalSystem = systemInterfaceMatcher.group(4);

					// add the entry to the respective map ordered by external system name
					if (direction.equalsIgnoreCase("IN")) {
						inboundInterfaces.put(externalSystem,
								String.format("<li>%s from %s (connector %s)</li>", dataType, externalSystem, connector));
					} else {
						outboundInterfaces.put(externalSystem,
								String.format("<li>%s to %s (connector %s)</li>", dataType, externalSystem, connector));
					}
				}

				// if external system interfaces were found
				if ((inboundInterfaces.size() > 0) || (outboundInterfaces.size() > 0)) {
					// remove the interface description from the channel description
					description = systemInterfaceMatcher.replaceAll("");

					// if there were any inbound interfaces detected (data coming from an application interface to the mirth channel)
					if (inboundInterfaces.size() > 0) {
						// add an inbound property
						metaData.accumulate("Inbound Interfaces",
								"<ol>\n" + inboundInterfaces.values().stream().collect(Collectors.joining("\n")) + "</ol>\n");
					}

					// if there were any outbound interfaces detected (data going to an application interface from the mirth channel)
					if (outboundInterfaces.size() > 0) {
						// add an outbound property
						metaData.accumulate("Outbound Interfaces",
								"<ol>\n" + outboundInterfaces.values().stream().collect(Collectors.joining("\n")) + "</ol>\n");
					}
				}

				// add the description of the channel
				metaData.accumulate("Description",
						description.replaceFirst("^[\\p{Cntrl}\\s]*", "").replaceFirst("[\\p{Cntrl}\\s]*$", "").replaceAll("\\r\\n|\\r|\\n", "<br>"));

				if (channelDisabled) {
					// indicate if channel is disabled
					metaData.accumulate("Is disabled", channelDisabled);
				}
				
				/** the following code accumulates the detected channel issues */
				
				// get a validated list of used functions
				TreeMap<String, String> validatedFunctions = validateFunctionReferences(channelId);

				// check for unknown functions
				TreeSet<String> unknownFunctions = getUnknownChannelFunctions(channelId);
				if (unknownFunctions != null) {
					// first check if there is already an issue attribute for this channel
					if (!metaData.has("Issues")) {
						// if not, create it
						metaData.put("Issues", new JSONObject());
					}
					// add the list of unknown functions
					metaData.getJSONObject("Issues").put("unknownFunctions", unknownFunctions);
				}

				// get the validated list of (to be) referenced libraries
				JSONObject libraryReferences = generateValidatedReferencedLibraryList(channelId,
						(validatedFunctions != null) ? validatedFunctions.keySet() : null);

				// check for missing code template library references
				JSONArray missingReferences = libraryReferences.getJSONArray("issues");
				// and if there are any
				if (missingReferences.length() > 0) {
					// first check if there is already an issue attribute for this channel
					if (!metaData.has("Issues")) {
						// if not, create it
						metaData.put("Issues", new JSONObject());
					}
					// add the list of missing libraries
					metaData.getJSONObject("Issues").put("missingReferences", missingReferences);
				}
				
				// write the meta data to cache
				channelInfo.put(metaData.getString("Id"), metaData);
			}
			this.channelInfo = channelInfo;
			// update the update indicator
			this.lastUpdate = System.currentTimeMillis();
		}

		return this.channelInfo;
	}
	
	/**
	 * Provides information about an external resource that is referenced by Mirth
	 * @param resourceName The name of the external resource
	 * @return A JSON object containing the following information:
	 * <ul>
	 * 	<li><b>name</b> - The name of the resource</li>
	 * 	<li><b>id</b> - The mirth internal id of the resource</li>
	 * 	<li><b>location</b> - The path to which the resource is pointing</li>
	 * 	<li><b>description</b> - The description text of the resource</li>
	 * 	<li><b>type</b> - The type of the resource</li>
	 * </ul>
	 * @throws ServiceUnavailableException 
	 */
	private JSONObject getExternalResource(String resourceName) throws ServiceUnavailableException {

		if(this.externalResources == null) {
			JSONObject resourceInfoRaw = null;
			this.externalResources = new HashMap<String, JSONObject>();

			// try to load the channel meta data from the API
			resourceInfoRaw = getResponseAsJson(connectToRestService("/api/server/resources"));
			
			try {
				// make sure the work continues w/ an JSON array
				JSONArray resourceInfo = (resourceInfoRaw.get("com.mirth.connect.plugins.directoryresource.DirectoryResourceProperties") instanceof JSONArray) ? resourceInfoRaw.getJSONArray("com.mirth.connect.plugins.directoryresource.DirectoryResourceProperties")
						: (new JSONArray()).put(resourceInfoRaw.get("com.mirth.connect.plugins.directoryresource.DirectoryResourceProperties"));
				// cache external resources info
				for (int index = 0; index < resourceInfo.length(); index++) {
					// fetch the relevant resource attributes
					String name = resourceInfo.getJSONObject(index).getString("name");
					String id = resourceInfo.getJSONObject(index).getString("id");
					String location = resourceInfo.getJSONObject(index).getString("directory");
					String description = resourceInfo.getJSONObject(index).getString("description");
					String type = resourceInfo.getJSONObject(index).getString("type");
					
					// create the object
					JSONObject entry = new JSONObject();
					entry.accumulate("name", name);
					entry.accumulate("id", id);
					entry.accumulate("location", location);
					entry.accumulate("description", description);
					entry.accumulate("type", type);
					
					// and add it to the cache
					this.externalResources.put(name, entry);
				}
			} catch (Exception e) {
				logger.error("Owh, getExternalResource() has to be revised! \n" + e.getMessage());
			}
		}

		// try to fetch the external resource by the given name
		return this.externalResources.get(resourceName);
	}

	/**
	 * Provides a cache of all last modified dates of channels by using the new Mirth API version
	 * 
	 * @param forceReload
	 *            A flag that causes, when set to true, the cache to reload regardless of its pre-existence
	 * @return The last modified cache for all channels or an empty cache if the Mirth server does not support the metadata web-service or is not
	 *         reachable
	 */
	private HashMap<String, Long> getChannelLastModified(boolean forceReload) {
		if (forceReload || (this.channelLastModified == null)) {
			cacheChannelMetaData();
		}

		return this.channelLastModified;
	}

	/**
	 * Provides a cache of all channel states by using the new Mirth API version
	 * 
	 * @param forceReload
	 *            A flag that causes, when set to true, the cache to reload regardless of its pre-existence
	 * @return The state cache for all channels or an empty cache if the Mirth server does not support the metadata web-service or is not reachable
	 */
	private HashMap<String, Boolean> getChannelState(boolean forceReload) {
		if (forceReload || (this.channelState == null)) {
			cacheChannelMetaData();
		}

		return this.channelState;
	}

	/**
	 * Caches the last modified date as well as the state of channels by using the new Mirth API (channelMetadata)
	 */
	private void cacheChannelMetaData() {

		// initialize the caches
		this.channelState = new HashMap<String, Boolean>();
		this.channelLastModified = new HashMap<String, Long>();

		JSONObject channelMetaData = null;
		try {
			// try to load the channel meta data from the API
			channelMetaData = getResponseAsJson(connectToRestService("/api/server/channelMetadata"));
			channelMetaData = channelMetaData.getJSONObject("map");
		} catch (Exception e) {
			// this may fail as it is not supported by older Mirth versions
			return;
		}
		try {
			// make sure the work continues w/ an JSON array
			JSONArray channel = (channelMetaData.get("entry") instanceof JSONArray) ? channelMetaData.getJSONArray("entry")
					: (new JSONArray()).put(channelMetaData.get("entry"));

			// add all last modified time stamps and channel states to the caches
			for (int index = 0; index < channel.length(); index++) {
				// get the id of the current channel
				String channelId = channel.getJSONObject(index).getString("string");
				// access the actual metadata section of the channel
				channelMetaData = channel.getJSONObject(index).getJSONObject("com.mirth.connect.model.ChannelMetadata");

				// there are corrupt configurations where the attribute is actually missing - no idea why...
				if (channelMetaData.has("lastModified")) {
					// add the last modified date for the channel to the cache
					this.channelLastModified.put(channelId, channelMetaData.getJSONObject("lastModified").getLong("time"));
				}

				// and add the channel state to the cache. Better check the attribute first to avoid potential issues like for lastModified
				this.channelState.put(channelId, channelMetaData.has("enabled") ? channelMetaData.getBoolean("enabled") : true);
			}
		} catch (Exception e) {
			logger.error("Owh, getChannelLastModified() has to be revised! \n" + e.getMessage());
		}
	}

	/**
	 * Detects the functions that are used by the channels by parsing the channel source code. It caches a mapping between channel and functions as
	 * well as function and channels
	 * 
	 * @param xml
	 *            The source code of the channels (channel definition)
	 * @throws ConfigurationException
	 */
	private synchronized void buildUpCodeTemplateRelationships(String xml) throws ConfigurationException {
// xxx
		String channelDefinition = null;
		Matcher channelMatcher, idMatcher, functionReferenceMatcher, functionNameMatcher;
		TreeSet<String> detectedFunctions = null;
		String channelId = null;

		channelMatcher = channelPattern.matcher(xml);
		// loop over all channels
		while (channelMatcher.find()) {
			// get the channel definition
			channelDefinition = channelMatcher.group();

			// detect the channel id
			idMatcher = idPattern.matcher(channelDefinition);
			if (!idMatcher.find()) {
				// should never happen
				throw new ConfigurationException("Unable to find channel id: \n" + channelDefinition);
			}
			// extract the channel id from the xml
			channelId = idMatcher.group(1);
			
			// prepare channel definition for function detection
			channelDefinition = prepareForFunctionParsing(channelDefinition);
			
			// needed to avoid detecting regular expressions as false positives
			Matcher roughRegexMatcher = roughRegexDetectionPattern.matcher(channelDefinition);		
			
			// create a container for the detected functions
			detectedFunctions = new TreeSet<String>();

			// now scan for functions
			functionReferenceMatcher = functionReferenceDetectionPattern.matcher(channelDefinition);

			int functionNameBegin = 0;
			int functionNameEnd = 0;
			// and add all detected functions to a distinct list
			while (functionReferenceMatcher.find()) {

				// get the next detected function
				String functionName = functionReferenceMatcher.group(1);
				// if the current detected function is part of the filter list
				if (functionFilter.contains(functionName)) {
					// omit it as it is a false positive (e.g. an SQL function)
					continue;
				}
				
				// if this reference was already detected
				if(detectedFunctions.contains(functionName)) {
					// no need to scan it again
					continue;
				}
				
				// determine the location of the function reference w/i the code
				functionNameBegin = functionReferenceMatcher.start(1);
				functionNameEnd = functionReferenceMatcher.end(1);

				boolean isRegex = false;
				// start the regex search over from the begin of the source code
				roughRegexMatcher.reset();

				// check all regular expressions
				while (roughRegexMatcher.find()) {
					// Check if this function call is inside a regular expression
					if (functionNameBegin > roughRegexMatcher.start() && functionNameEnd < roughRegexMatcher.end()) {
						// Oops this was a regex
						isRegex = true;
						break;
					}
					// if the regex already starts after the function name
					if (functionNameEnd < roughRegexMatcher.start()) {
						// no further false positive scanning for this function is needed
						break;
					}
				}
				
				// if function reference was detected as regex
				if(isRegex) {
					// ignore it
					continue;
				}

				// make it sexy
				functionName += "()";
				// add the function to the result set
				detectedFunctions.add(functionName);
				// as there seem to be concurrency situations where the container is removed during it's filling 
				if(this.channelReferencesToFunction == null) {
					// add this for security reasons
					this.channelReferencesToFunction = new HashMap<String, TreeSet<String>>();
					logger.warn("There seems to be a concurrency issue. Container channelReferencesToFunction was deleted when it was about to be filled.");
				}
				// if there is not yet a container for this function
				if (!this.channelReferencesToFunction.containsKey(functionName)) {
					// create one - that way a bidirectional mapping is possible (allows to show which channel is using this function)
					this.channelReferencesToFunction.put(functionName, new TreeSet<String>());
				}
				// and add the reference to this channel to the list of channels using this function
				this.channelReferencesToFunction.get(functionName).add(channelId);
			}

			// if referenced functions where detected
			if (detectedFunctions.size() > 0) {
				// an entry for the channel
				this.channelFunctionReferences.put(channelId, new ArrayList<String>(detectedFunctions));
			}

			// function calls have been handled. Now check if there are function definitions in the channel itself
			functionNameMatcher = functionNamePattern.matcher(channelDefinition);
			ArrayList<String> channelFunctions = new ArrayList<String>();
			// scan channel definition for internal functions
			while (functionNameMatcher.find()) {
				// add the function to the list
				channelFunctions.add(functionNameMatcher.group(1) + "()");
			}
			// if internal functions where found
			if (channelFunctions.size() > 0) {
				// add them to cache
				this.channelInternalFunctionsByChannelId.put(channelId, channelFunctions);
			}
		}
	}

	/**
	 * Cleans the code to prepare it for function detection. The function aims to remove the following elements from the code in order to avoid false
	 * positives at function parsing:
	 * <ul>
	 * <li>Code comments</li>
	 * <li>Object instantiations (new Xyz())</li>
	 * <li>Regex patterns</li>
	 * <li>Java Strings (text in quotes)</li>
	 * <li>JavaScript Strings (text in apostrophes)</li>
	 * <li>Description tags</li>
	 * <li>Select tags</li>
	 * </ul>
	 * 
	 * @param code
	 *            The code that should be prepared for parsing
	 * @return The cleaned up code
	 */
	private String prepareForFunctionParsing(String code) {
		String result = null;
		
		if ((code != null) && (code.length() > 0)) {
			// remove all base64 blocks (it will otherwise bust the stack)
			result = base64DetectionPattern.matcher(code).replaceAll("$1");
			// remove all comments
			result = commentDetectionPattern.matcher(code).replaceAll("");
			// and also remove all object instantiations (like "new String()")
			result = instantationDetectionPattern.matcher(result).replaceAll("");
			// remove all java strings
			// IMPORTANT: This must be done before filtering JavaScript strings as apostrophes are also used in text
			result = javaStringDetectionPattern.matcher(result).replaceAll("");
			// remove all JavaScript strings
			result = javascriptStringDetectionPattern.matcher(result).replaceAll("");
			// remove all "<description>" tags
			result = descriptionTagDetectionPattern.matcher(result).replaceAll("");
			// remove all "<name>" tags
			result = nameTagDetectionPattern.matcher(result).replaceAll("");			
			// remove all "<subject>" tags
			result = subjectTagDetectionPattern.matcher(result).replaceAll("");			
			// remove all CDATA query definitions
			result = cdataDetectionPattern.matcher(result).replaceAll("");
			// remove all sql queries that are defined in query tags
			result = queryDetectionPattern.matcher(result).replaceAll("");
			// remove all sql queries that are defined in empty tags
			result = emptyTagDetectionPattern.matcher(result).replaceAll("");
			// and finally remove all "<select>" tags
			result = selectTagDetectionPattern.matcher(result).replaceAll("");
		}

		return result;
	}

	/**
	 * Provides a list of Mirth environments
	 * 
	 * @return A JSON array or Mirth environments ordered by the position id with the following structure:
	 *         <ul>
	 *         <li><b>id</b> - The identifier of the environment</li>
	 *         <li><b>position</b> - The order position of the environment</li>
	 *         <li><b>name</b> - The name of the environment</li>
	 *         <li><b>color</b> - The color that should be used to show mirth instances belonging to this environment</li>
	 *         </ul>
	 */
	public static NativeObject getEnvironments() {
		JSONArray result = new JSONArray();

		try {
			if (MirthMigrator.mirthEnvironments == null) {
				// assure that the configuration was loaded
				loadConfiguration();
			}

			// order configurations by their id
			TreeMap<String, HashMap<String, String>> environments = new TreeMap<String, HashMap<String, String>>();
			Iterator<HashMap<String, String>> values = MirthMigrator.mirthEnvironments.values().iterator();
			while (values.hasNext()) {
				HashMap<String, String> environment = values.next();
				environments.put(environment.get("position"), environment);
			}

			// get hold of the environments ordered by their position attribute
			values = environments.values().iterator();
			// and add all environments to the result
			while (values.hasNext()) {
				// create a new environment entry
				HashMap<String, String> environment = values.next();
				JSONObject entry = new JSONObject();
				// add the id
				entry.put("id", environment.get("id"));
				// add the order position
				entry.put("position", environment.get("position"));
				// it's name
				entry.put("name", environment.get("name"));
				// and it's color
				entry.put("color", environment.get("color"));

				// and add the environment to the list
				result.put(entry);
			}
		} catch (IOException e) {
			return createReturnValue(500, "Unable to load configuration: \n" + e.getMessage());
		} catch (ConfigurationException e) {
			return createReturnValue(500, "Corrupt configuration: \n" + e.getMessage());
		} catch (ServiceUnavailableException e) {
			return createReturnValue(503, "Service unavailable: \n" + e.getMessage());
		}

		return createReturnValue(200, result);
	}

	/**
	 * Provides a list of Mirth instances
	 * 
	 * @return A JSON array or Mirth instances ordered by the instance name with the following structure:
	 *         <ul>
	 *         <li><b>name</b> - The name of the mirth instance</li>
	 *         <li><b>environment</b> - The environment to which the mirth instance belongs</li>
	 *         <li><b>color</b> - The color of the environment to which the mirth instance belongs</li>
	 *         <li><b>description</b> - The description of the mirth instance</li>
	 *         <li><b>server</b> - The server at which the mirth system is located</li>
	 *         <li><b>port</b> - The port at which the system listens</li>
	 *         </ul>
	 */
	public static NativeObject getSystems() {
		JSONArray result = new JSONArray();

		try {
			if (MirthMigrator.mirthClients == null) {
				// assure that the configuration was loaded
				loadConfiguration();
			}

			// order the mirth systems by their names
			Iterator<MirthMigrator> systems = (new TreeMap<String, MirthMigrator>(MirthMigrator.mirthClients)).values().iterator();
			// and add the
			while (systems.hasNext()) {
				MirthMigrator system = systems.next();
				JSONObject entry = new JSONObject();
				// add the id
				entry.put("name", system.getSystemName());
				entry.put("environment", system.getEnvironment());
				entry.put("environmentOrderId", MirthMigrator.getEnvironment(system.getEnvironment()).get("position"));
				entry.put("color", getEnvironment(system.getEnvironment()).get("color"));
				entry.put("description", system.getDescription());
				entry.put("server", system.getServer());
				entry.put("port", system.getPort());

				// and add the system to the list
				result.put(entry);
			}

		} catch (IOException e) {
			return createReturnValue(500, "Unable to load configuration: \n" + e.getMessage());
		} catch (ConfigurationException e) {
			return createReturnValue(500, "Corrupt configuration: \n" + e.getMessage());
		} catch (ServiceUnavailableException e) {
			return createReturnValue(503, "Service unavailable: \n" + e.getMessage());
		}

		return createReturnValue(200, result);
	}

	/**
	 * Provides the Mirth Migrator configuration.
	 * 
	 * @return The Mirth Migrator configuration. If there is not yet a configuration file, or if the configuration is not accessible or corrupt, it
	 *         provides the default configuration template.
	 */
	public static NativeObject getConfiguration() {
		JSONObject configuration = MirthMigrator.configuration;

		// if the configuration file has not yet been loaded
		if (configuration == null) {
			try {
				// try to load it
				loadConfiguration();
				configuration = MirthMigrator.configuration;
			} catch (ServiceUnavailableException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (ConfigurationException e) {
				// If it was not found or could not be loaded, use the default configuration template from the jar
				InputStream configurationTemplate = MirthMigrator.class.getClassLoader().getResourceAsStream("ConfigurationTemplate");
	            if (configurationTemplate == null) {
	                throw new IllegalArgumentException("Unable to find the ConfigurationTemplate resource in the jar (root location is \""+MirthMigrator.class.getClassLoader().getResource("")+"\")");
	            }
				try {
					// and create a JSOn object from the template
					configuration = new JSONObject(IOUtils.toString(configurationTemplate, "UTF-8"));
					logger.warn("There is not yet a configuration, loading default configuration template");
				} catch (Exception e1) {
					logger.error("We got an Oooopsi!\n"+e1.getMessage());
					e1.printStackTrace();
				} finally {
					// assure that resources are freed
					try{configurationTemplate.close();}catch(Exception ex) {}
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		// return the configuration
		return createReturnValue(200, configuration);
	}
	
	/**
	 * Writes the configuration file
	 * @param configuration The Mirth Migrator configuration to which the configuration file should be updated
	 */
	public static NativeObject setConfiguration(NativeObject configuration) {

		Context context = Context.enter();
		try {
			// Convert the JavaScript object to a JSON string
			String config = Context.toString(NativeJSON.stringify(context, context.initStandardObjects(), configuration, null, null));

			try {
				// get the path to which the configuration file is written
				Path configLocation = Paths.get(configurationFileLocation);
				// make sure that the intended path actually exists
				Files.createDirectories(configLocation.getParent());
				// and write the configuration to file
				Files.write(configLocation, config.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				
				logger.debug("Successfully wrote configuration to \"" + configLocation.toAbsolutePath() + "\"");
			} catch (IOException e) {
				e.printStackTrace();
			}
		} finally {
			// Exit from the context
			Context.exit();
		}

		return createReturnValue(200, null);
	}
	
	/**
	 * Provides the metadata and source code of a specific component
	 * 
	 * @param component
	 *            {@link #CHANNEL_GROUP}, {@link #CHANNEL}, {@link #CODE_TEMPLATE_LIBRARY}, or {@link #CODE_TEMPLATE}
	 * @return A JSON object containing the following information:
	 *         <ul>
	 *         <li><b>success</b> - The status that indicates if the operation was successful (true) or not (false)</li>
	 *         <li><b>statusCode</b> - The HTTP return code (e.g. 200 in case of success)</li>
	 *         <li><b>payload</b> - the actual payload of the request with the following structure:
	 *         <ul>
	 *         <li><b>name</b> - the name of the component</li>
	 *         <li><b>id</b> - the id of the component</li>
	 *         <li><b>lastModified</b> - the date of the last modification as timestamp <i>(in ms)</i></li>
	 *         <li><b>DisplayDate</b> - the date of the last modification in a human readable format</li>
	 *         <li><b>revision</b> - the revision number of the component</li>
	 *         <li><b>type</b> - the type of the component</li>
	 *         <li><b>description</b> - the description of the component</li>
	 *         <li><b>content</b> - the source code of the component <i>(only for {@link #CHANNEL} and {@link #CODE_TEMPLATE})</i></li>
	 *         <li><b>subComponentsCount</b> - the number of child elements <i>(only for {@link #CHANNEL_GROUP} and
	 *         {@link #CODE_TEMPLATE_LIBRARY})</i></li>
	 *         </ul>
	 *         </li>
	 *         </ul>
	 *         <font color="red"><i>If no corresponding component was found, the function returns no payload</i></font>
	 */
	public NativeObject getComponentDetails(NativeObject component) {
		// extract the type of the component
		String componentType = (String) component.get("type", null);
		// and also it's identifier
		String componentId = (String) component.get("id", null);

		try {
			// obtain the details and return them
			return createReturnValue(200, getComponentDetails(componentType, componentId, false));
		} catch (ConfigurationException e) {
			// an invalid configuration was detected
			return createReturnValue(500, "Corrupt configuration (either in \"" + configurationFileLocation + "\" or in the " + componentType
					+ " definitions in the Mirth instance \"" + getServer() + "\" itself: \n" + e.getMessage());
		} catch (ServiceUnavailableException e) {
			// the target system is not available
			return createReturnValue(503, e.getMessage());
		}
	}

	/**
	 * Provides the metadata and source code of a specific component
	 * 
	 * @param componentType
	 *            {@link #CHANNEL_GROUP}, {@link #CHANNEL}, {@link #CODE_TEMPLATE_LIBRARY}, or {@link #CODE_TEMPLATE}
	 * @param componentId
	 *            The unique id of the component
	 * @return A JSON object containing the following information:
	 *         <ul>
	 *         <li><b>name</b> - the name of the component</li>
	 *         <li><b>id</b> - the id of the component</li>
	 *         <li><b>lastModified</b> - the date of the last modification as timestamp <i>(in ms)</i></li>
	 *         <li><b>DisplayDate</b> - the date of the last modification in a human readible format</li>
	 *         <li><b>revision</b> - the revision number of the component</li>
	 *         <li><b>type</b> - the type of the component</li>
	 *         <li><b>description</b> - the description of the component</li>
	 *         <li><b>content</b> - the source code of the component <i>(only for {@link #CHANNEL} and {@link #CODE_TEMPLATE})</i></li>
	 *         <li><b>subComponentsCount</b> - the number of child elements <i>(only for {@link #CHANNEL_GROUP} and
	 *         {@link #CODE_TEMPLATE_LIBRARY})</i></li>
	 *         </ul>
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 */
	private JSONObject getComponentDetails(String componentType, String componentId) throws ConfigurationException, ServiceUnavailableException {
		return getComponentDetails(componentType, componentId, false);
	}

	/**
	 * Provides the metadata and code of a specific component
	 * 
	 * @param componentType
	 *            {@link #CHANNEL_GROUP}, {@link #CHANNEL}, {@link #CODE_TEMPLATE_LIBRARY}, or {@link #CODE_TEMPLATE}
	 * @param componentId
	 *            The unique id of the component
	 * @param getNameOnly
	 *            <b>true</b> (Return only the name attribute of the component) or <b>false</b> (return the whole information set)
	 * @return A JSON object containing the following information:
	 *         <ul>
	 *         <li><b>name</b> - the name of the component</li>
	 *         <li><b>id</b> - the id of the component</li>
	 *         <li><b>lastModified</b> - the date of the last modification as timestamp <i>(in ms)</i></li>
	 *         <li><b>DisplayDate</b> - the date of the last modification in a human readable format</li>
	 *         <li><b>revision</b> - the revision number of the component</li>
	 *         <li><b>type</b> - the type of the component</li>
	 *         <li><b>description</b> - the description of the component</li>
	 *         <li><b>content</b> - the source code of the component <i>(only for {@link #CHANNEL} and {@link #CODE_TEMPLATE})</i></li>
	 *         <li><b>subComponentsCount</b> - the number of child elements <i>(only for {@link #CHANNEL_GROUP} and
	 *         {@link #CODE_TEMPLATE_LIBRARY})</i></li>
	 *         </ul>
	 *         <font color="red"><i>If no corresponding component was found, the function returns <b>null</b></i></font>
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 */
	private JSONObject getComponentDetails(String componentType, String componentId, boolean getNameOnly)
			throws ConfigurationException, ServiceUnavailableException {

		// dispatch
		switch (componentType) {
		case CHANNEL:
			return getChannelDetails(componentId, getNameOnly);
		case CHANNEL_GROUP:
			return getChannelGroupDetails(componentId, getNameOnly);
		case CODE_TEMPLATE:
			return getCodeTemplateDetails(componentId, getNameOnly);
		case CODE_TEMPLATE_LIBRARY:
			return getCodeTemplateLibraryDetails(componentId, getNameOnly);
		default:
			throw new ConfigurationException("Unsupported component type \"" + componentType + "\" has been specified.");
		}

	}

	/**
	 * Assembles detail information for a channel
	 * 
	 * @param channelId
	 *            The id of the channel
	 * @param nameOnly
	 *            If this flag is set to true, only the name of the channel is provided
	 * @return If the nameOnly-flag is set to true:<br>
	 *         <ul>
	 *         <li><b>Name</b>- The name of the channel</li>
	 *         </ul>
	 *         If the nameOnly flag is set to false, the following structure:
	 *         <ul>
	 *         <li><b>Name</b> - The name of the channel</li>
	 *         <li><b>Type</b> - Always <b>Channel</b></li>
	 *         <li><b>Description</b> - A description of the channel</li>
	 *         <li><b>Changes</b> - A description changes that have been applied to the channel</li>
	 *         <li><b>Outbound Interfaces</b> - A description of the outbound interfaces to external systems</li>
	 *         <li><b>Inbound Interfaces</b> - A description of the inbound interfaces from external systems</li>
	 *         <li><b>Version</b> - The version of the channel</li>
	 *         <li><b>Display date</b> - The date and time of the last modification of the channel in a human readable format</li>
	 *         <li><b>Last modified</b> - The date and time of the last modification of the channel in milliseconds</li>
	 *         <li><b>Id</b> - The UUID of the channel</li>
	 *         <li><b>Channel status</b> - either <b>Enabled</b> or <b>Disabled</b></li>
	 *         </ul>
	 *         <font color="red"><i>If no corresponding channel was found, the function returns <b>null</b></i></font>
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 */
	private JSONObject getChannelDetails(String channelId, boolean nameOnly) throws ConfigurationException, ServiceUnavailableException {
		JSONObject result = new JSONObject();
		// get the cached information about the channel
		JSONObject channel = getChannelInfoById(channelId);
		if (channel == null) {
			// channel does not exist on this server
			return null;
		}

		// and assemble a new json object that just contains the information needed by the client
		result.accumulate("Name", "<b>" + channel.getString("Display name") + "</b>"
				+ (channel.has("Is disabled") ? " (<font color=\"red\"><b>Disabled</b></font>)" : ""));

		if (nameOnly) {
			// if just a mapping between component id and component name was
			// needed, the work is done here
			return result;
		}
		// indicate the type
		result.accumulate("Type", "Channel");
		// as description is optional, only add the attribute if there actually is a description
		if (channel.has("Description")) {
			// the description of the library
			result.accumulate("Description", channel.getString("Description"));
		}

		// the documentation of changes that have been applied to the channel
		if (channel.has("Changes")) {
			result.accumulate("Changes", channel.getString("Changes"));
		}

		// the outbound interfaces to external systems
		if (channel.has("Outbound Interfaces")) {
			result.accumulate("Outbound Interfaces", channel.getString("Outbound Interfaces"));
		}

		// the inbound interfaces from external systems
		if (channel.has("Inbound Interfaces")) {
			result.accumulate("Inbound Interfaces", channel.getString("Inbound Interfaces"));
		}
		// the version of the channel (revision)
		result.accumulate("Version", channel.getInt("Version"));
		// the date of the last modification
		if (channel.has("Last modified")) {
			result.accumulate("Last modified", channel.getLong("Last modified"));
		}
		// the human readable date of the last modification
		if (channel.has("Display date")) {
			result.accumulate("Display date", channel.getString("Display date"));
		}
		// the id of the channel
		result.accumulate("Id", channelId);

		// get a validated list of used functions
		TreeMap<String, String> validatedFunctions = validateFunctionReferences(channelId);
		if (validatedFunctions != null) {
			// get a list of functions used by the channel and add it to the metadata
			result.put("Uses functions", validatedFunctions.values());
		}
		
		// get the validated list of (to be) referenced libraries
		JSONObject libraryReferences = generateValidatedReferencedLibraryList(channelId,
				(validatedFunctions != null) ? validatedFunctions.keySet() : null);
		JSONArray libraries = libraryReferences.getJSONArray("libraries");
		// and if there are any
		if (libraries.length() > 0) {
			// add the list of referenced channel libraries
			result.put("Referenced Libraries", libraries);
		}
	
		// now load the channel code
		String code = getResponseAsXml(connectToRestService("/api/channels?channelId=" + channelId));
		// decode xml
		code = code.replaceAll("&amp;", "&").replaceAll("&quot;", "\"").replaceAll("&apos;", "'").replaceAll("&gt;", ">").replaceAll("&lt;", "<")
				.replaceAll("&#xd;", "\n");
		// remove the embracing list tag from the channel definition
		code = code.replaceAll("^\\s*<list>", "").replaceAll("</list>\\s*$", "");
		// and add it to the structure
		result.accumulate("content", code);

		return result;
	}
	
	
	/**
	 * Provides a list of functions that are directly defined in the channel
	 * 
	 * @param channelId
	 *            The id of the channel
	 * @return A list of function names or null if there are no functions that are defined in the channel itself
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 */
	private ArrayList<String> getChannelInternalFunctions(String channelId) throws ConfigurationException, ServiceUnavailableException {
		// check if the list of channel internal functions has been loaded
		if (this.channelInternalFunctionsByChannelId == null) {
			// nope, create it
			getChannelInfo();
		}

		return this.channelInternalFunctionsByChannelId.get(channelId);
	}

	/**
	 * Provides meta information for a channel
	 * 
	 * @param id
	 *            The id of the channel
	 * @return The meta information of a channel or null if a channel with the provided id does not exist
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 * 
	 */
	private JSONObject getChannelInfoById(String id) throws ConfigurationException, ServiceUnavailableException {
		return getChannelInfo().get(id);
	}

	
	/**
	 * Provides a list of functions that are used by the channel
	 * 
	 * @param channelId
	 *            The id of the channel
	 * @return A list of functions used by the channel or null if none are used
	 * @throws ServiceUnavailableException 
	 * @throws ConfigurationException 
	 */
	private ArrayList<String> getChannelFunctionReferences(String channelId) throws ConfigurationException, ServiceUnavailableException {
		if(this.channelFunctionReferences == null) {
			getChannelInfo();
		}

		ArrayList<String> usedFunctions = this.channelFunctionReferences.get(channelId);

		return ((usedFunctions == null) || (usedFunctions.size() == 0)) ? null : usedFunctions;
	}

	/**
	 * Provides a list of code template libraries that are referenced by a channel
	 * 
	 * @param channelId
	 *            The id of the channel
	 * @return A list of code template libraries that are referenced by the channel or null if none are referenced
	 * @throws ServiceUnavailableException
	 */
	private ArrayList<String> getChannelCodeTemplateLibraryReferences(String channelId) throws ServiceUnavailableException {
		// if not yet done
		if (this.channelCodeTemplateLibraryReferences == null) {
			// generate the required information, first
			getCodeTemplateLibraryInfo();
		}

		ArrayList<String> referencedLibaries = this.channelCodeTemplateLibraryReferences.get(channelId);

		return ((referencedLibaries == null) || (referencedLibaries.size() == 0)) ? null : referencedLibaries;
	}

	/**
	 * Checks if functions are properly linked to a channel
	 * 
	 * @param channelId The id of the channel for which the function references should be validated
	 * @return An ordered map of function names and their display strings or null if no or an empty function list was provided
	 * @throws ServiceUnavailableException
	 * @throws ConfigurationException 
	 */
	private TreeMap<String, String> validateFunctionReferences(String channelId) throws ServiceUnavailableException, ConfigurationException {
		

		// get the list of referenced libraries
		ArrayList<String> referencedLibraries = getChannelCodeTemplateLibraryReferences(channelId);
		if (referencedLibraries == null) {
			// no libraries have been referenced - create a dummy
			referencedLibraries = new ArrayList<String>();
		}
		
		// parse the channel
		ArrayList<String> usedFunctions = getChannelFunctionReferences(channelId);


		ArrayList<String> channelFunctions = getChannelInternalFunctions(channelId);
		
		return validateFunctionReferences(channelId, null, referencedLibraries, usedFunctions, channelFunctions, null);
	}

	/**
	 * Checks if functions are properly linked to a channel
	 * 
	 * @param channelId
	 *            The ID of the channel for which the function references should be validated (only needed if functions used by a channel should be validated)
	 * @param rootFunctionId
	 *            The ID of the channel for which the function references should be validated  (only needed if functions of a function should be validated)
	 * @param referencedLibraries
	 *            A list of IDs of libraries referenced by the channel
	 * @param referencedFunctions
	 *            A list of names of functions used by the channel
	 * @param channelFunctions
	 *            (OPTIONAL) A list of functions that are defined within the channel
	 * @param parentFunctionPath
	 *            (OPTIONAL) IF a function is used by another function, the using function is indicated here (this is only used at recursive calls)
	 * @return An ordered map of function names and their display strings or null if no or an empty function list was provided
	 * @throws ServiceUnavailableException
	 */
	private TreeMap<String, String> validateFunctionReferences(String channelId, String rootFunctionId, ArrayList<String> referencedLibraries, ArrayList<String> referencedFunctions,
			ArrayList<String> channelFunctions, String parentFunctionPath) throws ServiceUnavailableException {

		TreeMap<String, String> validatedFunctionReferences;
		// check if there are any functions for validation
		if ((referencedFunctions == null) || (referencedFunctions.size() == 0)) {
			return null;
		}

		validatedFunctionReferences = new TreeMap<String, String>();
		String functionLibId, displayName, functionId, functionPath, diplayColor, codeTemplateLibraryName = null;
		ArrayList<String> indirectFunctions = null;

		// get the list of code template to code template library dependencies
		HashMap<String, String> codeTemplateToLibary = getCodeTemplateLibraryIdByCodeTemplateId();

		// validate each function
		for (String functionName : referencedFunctions) {
			// check for recursions. Also indirect recursions should be detected
			if ((parentFunctionPath != null) && (parentFunctionPath.startsWith(functionName) || parentFunctionPath.contains(' ' + functionName + ' ')
					|| (parentFunctionPath.endsWith(' ' + functionName)))) {
				// recursive call detected - skip this function
				continue;
			}

			// fetch the id of the current code template id
			functionId = getCodeTemplateIdByName().get(functionName);

			// first check if the function itself is part of a referenced library or of no library at all

			// if the function belongs to a library
			if (codeTemplateToLibary.containsKey(functionId)) {

				// fetch the id of the library to which the function belongs
				functionLibId = codeTemplateToLibary.get(functionId);
				// get the name of the corresponding code template library
				codeTemplateLibraryName = getCodeTemplateLibraryInfoById(functionLibId).getString("Display name");
				// determine in which color the reference should be displayed. Green if the reference is valid and red if the code template
				// library to which the referenced function belongs was identified. However this library is not referenced by the channel
				diplayColor = referencedLibraries.contains(functionLibId) ? "green" : "red";
				// library containing the channel is properly linked to the channel and thus, the function is accessible
				displayName = String.format("<font color='%s'><b>%s</b></font> [%s]", diplayColor, functionName, codeTemplateLibraryName);

			} else {

				// the function is defined w/i a channel
				if ((channelFunctions != null) && (channelFunctions.contains(functionName))) {
					displayName = String.format(
							"<font color='Green'><b>%s</b></font> [<font color='DarkOrange'><b><i>defined in channel</i></b></font>]", functionName);

				} else {
					// function is neither referenced nor part of any library
					displayName = String.format("<font color='DarkOrange'><b>%s</b></font> [<font color='Red'><b>unknown source</b></font>]",
							functionName);
					// add the function name to the list of unknown functions that have been referenced by the channel
					addUnknownChannelFunction(channelId, functionName);
				}
			}
			// create the new function path
			functionPath = (parentFunctionPath != null) ? parentFunctionPath + " ==> " : "";

			// add the formated function information
			validatedFunctionReferences.put(functionPath + functionName, functionPath + displayName);

			// now check if the current code template references
			indirectFunctions = getFunctionUsesFunctions().get(functionName);
			if (indirectFunctions != null) {
				// and add them to the list
				validatedFunctionReferences
						.putAll(validateFunctionReferences(channelId, rootFunctionId, referencedLibraries, indirectFunctions, channelFunctions, functionPath + functionName));
			}
		}

		return validatedFunctionReferences;
	}

	/**
	 * Adds an unknown function that is referenced by a channel to an issue list
	 * 
	 * @param channelId
	 *            The id of the channel for which the function should be added to the issue list
	 * @param functionName
	 *            The name of the unknown function reference
	 */
	private void addUnknownChannelFunction(String channelId, String functionName) {
		// if the unknown channel function cache does not yet exist
		if (this.unknownChannelFunctions == null) {
			// create it
			this.unknownChannelFunctions = new HashMap<String, TreeSet<String>>();
		}

		// if the cache does not contain a record for this channel
		if (!this.unknownChannelFunctions.containsKey(channelId)) {
			// create it
			this.unknownChannelFunctions.put(channelId, new TreeSet<String>());
		}

		// add the function to the issue list
		this.unknownChannelFunctions.get(channelId).add(functionName);
	}

	/**
	 * Provides a list of unknown functions that are referenced by a channel
	 * 
	 * @param channelId
	 *            The ID of the channel
	 * @return An ordered list of unknown functions that are referenced by the provided channel or null if there are none
	 */
	private TreeSet<String> getUnknownChannelFunctions(String channelId) {
		return (this.unknownChannelFunctions != null) ? this.unknownChannelFunctions.get(channelId) : null;
	}

	/**
	 * Provides a list of libraries that have to be referenced by a channel. Green means the library is properly referenced, red means it is not
	 * referenced but should be, grey means it is referenced but not needed.
	 * 
	 * @param channelId
	 *            The id of the channel for which the list should be generated
	 * @param usedFunctions
	 *            A list of names of functions used by the channel
	 * @return A JSON object containing the following information:
	 * <ul>
	 * <li><b>issues</b> - a list of missing code template libraries</li>
	 * <li><b>libraries</b> - a list of names of code template libraries that are either needed and referenced (green), needed but not yet referenced (red), or
	 *         referenced but not detected as needed (grey)</li>
	 * </ul>
	 * 
	 * @throws ServiceUnavailableException
	 */
	private JSONObject generateValidatedReferencedLibraryList(String channelId, Set<String> usedFunctions)
			throws ServiceUnavailableException {
		TreeMap<String, String> libraries = new TreeMap<String, String>();
		String functionName = null;
		String functionId = null;
		String libraryId = null;
		String libraryName = null;
		String displayName = null;
		
		JSONObject result = new JSONObject();
		HashSet<String> issues = new HashSet<String>();

		// if no function set has been provided
		if (usedFunctions == null) {
			// create a dummy
			usedFunctions = new TreeSet<String>();
		}
		
		// get the list of referenced libraries
		ArrayList<String> referencedLibraries = getChannelCodeTemplateLibraryReferences(channelId);
		if (referencedLibraries == null) {
			// no libraries have been referenced - create a dummy
			referencedLibraries = new ArrayList<String>();
		}

		for (String functionPath : usedFunctions) {
			// check if it is a function name or path
			int index = functionPath.lastIndexOf(' ') + 1;
			// and extract the actual function name
			functionName = (index > 0) ? functionPath.substring(index) : functionPath;
			// now get the corresponding function id
			functionId = getCodeTemplateIdByName().get(functionName);
			// if there is no function id, the function source is unknown
			if (functionId == null) {
				// go on with the next function
				continue;
			}
			// and from there get the id of the library to which the functions belongs
			libraryId = getCodeTemplateLibraryIdByCodeTemplateId().get(functionId);
			// if no referenced library could be found for the function
			if (libraryId == null) {
				// go on w/ the next one
				continue;
			}

			// and finally the library name
			libraryName = getCodeTemplateLibraryInfoById(libraryId).getString("Display name");

			// depending on if the library is already referenced, display its name in green or in red, if not
			displayName = String.format("<font color='%s'><b>%s</b></font>", referencedLibraries.contains(libraryId) ? "green" : "red", libraryName);

			// add the library to the list
			libraries.putIfAbsent(libraryName, displayName);
			if(!referencedLibraries.contains(libraryId)) {
				issues.add(libraryName);
			}
		}

		// finally add the libraries that are referenced but not (detected as) needed
		for (String referencedLibraryId : referencedLibraries) {
			String referencedLibraryName = getCodeTemplateLibraryInfoById(referencedLibraryId).getString("Display name");
			// if library is not in the list
			if (!libraries.containsKey(referencedLibraryName)) {
				// it is not needed for the channel - indicate this to the user
				libraries.put(referencedLibraryName, String.format("<font color='grey'><b><i>%s</i></b></font>", referencedLibraryName));
			}
		}

		result.put("libraries", libraries.values());
		result.put("issues", issues);
		
		return result;
	}

	
	
	/**
	 * Assembles detail information for a channel group
	 * 
	 * @param libraryId
	 *            The id of the channel group
	 * @param nameOnly
	 *            If this flag is set to true, only the name of the library is provided
	 * @return If the nameOnly-flag is set to true:
	 *         <ul>
	 *         <li><b>Name</b> - The name of the channel group</li>
	 *         </ul>
	 *         If the nameOnly-flag is set to false, the following structure:<br>
	 *         <ul>
	 *         <li><b>Name</b> - The name of the channel group</li>
	 *         <li><b>Type</b> - Always <b>Channel Group</b></li>
	 *         <li><b>Description</b> - A description of the channel group</li>
	 *         <li><b>Version</b> - The version of the channel group</li>
	 *         <li><b>Display date</b> - The date and time of the last modification of the channel group in a human readable format</li>
	 *         <li><b>Last modified</b> - The date and time of the last modification of the channel group in milliseconds</li>
	 *         <li><b>Id</b> - The UUID of the channel group</li>
	 *         <li><b>Number of channels</b> - The number of channels that are referencing this group</li>
	 *         </ul>
	 *         <font color="red"><i>If no corresponding channel group was found, the function returns <b>null</b></i></font>
	 * @throws ServiceUnavailableException
	 * @throws ConfigurationException
	 * 
	 */
	private JSONObject getChannelGroupDetails(String libraryId, boolean nameOnly) throws ConfigurationException, ServiceUnavailableException {
		String value;
		JSONObject result = new JSONObject();

		// get the cached information about the channel group
		JSONObject group = getChannelGroupInfoById(libraryId);

		if (group == null) {
			// channel group does not exist on this server
			return null;
		}

		// and assemble a new json object that just contains the information needed by the client
		result.accumulate("Name", group.getString("Display name"));

		if (nameOnly) {
			// if just a mapping between component id and component name was
			// needed, the work is done here
			return result;
		}
		// indicate the type
		result.accumulate("Type", "Channel Group");
		// as description is optional, only add the attribute if there actually is a description
		value = group.getString("Description");

		// the description of the channel group
		result.accumulate("Description", !value.isEmpty() ? group.getString("Description") : "-");

		// the version of the channel group (revision)
		result.accumulate("Version", group.getInt("Version"));
		// check if it is a real group or the artificial group of unassigned channels
		boolean isReal = !group.getBoolean("artificial");
		// the date of the last modification
		result.accumulate("Last modified", isReal ? group.getLong("Last modified") : 0);
		// the human readable date of the last modification
		result.accumulate("Display date", isReal ? group.getString("Display date") : "");
		// the id of the channel group
		result.accumulate("Id", group.getString("Id"));
		// add info about number of channels that belong to this channel group
		result.accumulate("Number of channels", group.get("Number of members"));

		return result;
	}

	/**
	 * Assembles detail information for a function from a code template respectively the code template itself if it does not contain a function
	 * 
	 * @param codeTemplateId
	 *            The id of the code template
	 * @param nameOnly
	 *            If this flag is set to true, only the name of the code template is provided
	 * @return If the nameOnly-flag is set to true:
	 *         <ul>
	 *         <li><b>Name</b> - The name of the code template</li>
	 *         </ul>
	 *         If the nameOnly flag is set to false, the following structure:<br>
	 *         <ul>
	 *         <li><b>Name</b> - The name of the code template the function is in</li>
	 *         <li><b>Function Name</b> - The name of the function</li>
	 *         <li><b>Type</b> - Always <b>Code Template</b></li>
	 *         <li><b>Description</b> - A description of the code template</li>
	 *         <li><b>Parameters</b> - The parameters of the code template function (<i>OPTIONAL</i>)</li>
	 *         <li><b>Return value</b> - The return value of the code template function (<i>OPTIONAL</i>)</li>
	 *         <li><b>Version</b> - The version of the code template</li>
	 *         <li><b>Display date</b> - The date and time of the last modification of the code template in a human readable format</li>
	 *         <li><b>Last modified</b> - The date and time of the last modification of the code template in milliseconds</li>
	 *         <li><b>Id</b> - The UUID of the code template</li>
	 *         <li><b>code template status</b> - <b>Enabled</b> or <b>Disabled</b></li>
	 *         </ul>
	 *         <font color="red"><i>If no corresponding code template was found, the function returns <b>null</b></font>
	 * @throws ServiceUnavailableException
	 * @throws ConfigurationException
	 * 
	 */
	private JSONObject getCodeTemplateDetails(String codeTemplateId, boolean nameOnly) throws ServiceUnavailableException, ConfigurationException {

		JSONObject result = new JSONObject();
		// get the cached information about the code template
		JSONObject codeTemplate = getCodeTemplateInfoById(codeTemplateId);

		if (codeTemplate == null) {
			// code template does not exist on this server
			return null;
		}

		// and assemble a new json object that just contains the information needed by the client
		String functionName = null;
		if (codeTemplate.has("Is function")) {
			functionName = codeTemplate.getString("Function name");
			result.accumulate("Function Name", functionName);
		}
		// and assemble a new json object that just contains the information needed by the client
		String codeTemplateName = codeTemplate.getString("Display name");
		result.accumulate("Name", codeTemplateName);
		result.accumulate("Template name", codeTemplateName);
		if (nameOnly) {
			// if just a mapping between component id and component name was
			// needed, the work is done here
			return result;
		}
		// indicate the type
		result.accumulate("Type", "Code Template");
		// as description is optional, only add the attribute if there actually is a description

		if (codeTemplate.has("Description")) {
			// the description of the library
			result.accumulate("Description", codeTemplate.getString("Description"));
		}
		if (codeTemplate.has("Parameters")) {
			// the description of the library
			result.accumulate("Parameters", codeTemplate.getString("Parameters"));
		}
		if (codeTemplate.has("Return value")) {
			// the description of the library
			result.accumulate("Return value", codeTemplate.getString("Return value"));
		}
		// the version of the code template (revision)
		result.accumulate("Version", codeTemplate.getInt("Version"));
		// the date of the last modification
		result.accumulate("Last modified", codeTemplate.getLong("Last modified"));
		// the human readable date of the last modification
		result.accumulate("Display date", codeTemplate.getString("Display date"));
		// the id of the code template
		result.accumulate("Id", codeTemplate.getString("Id"));
		// if function references for channels have not yet been fetched
		if (this.channelReferencesToFunction == null) {
			// load channel information as this also parses channels for function references
			getChannelInfo();
		}

		// load a list of channels that reference this functions (if any)
		if ((functionName != null) && (getChannelReferencesToFunction().containsKey(functionName))) {
			// get the list of channels referencing the function
			Collection<String> referencingChannels = getChannelReferencesToFunction().get(functionName);
			// and add the validated list to the code template details
			result.put("Referenced by channels", generateValidatedLinkedByChannelList(referencingChannels, functionName));
		}

		// load a list of functions that reference this functions (if any)
		if ((functionName != null) && (this.functionLinkedByFunctions != null) && (this.functionLinkedByFunctions.containsKey(functionName))) {
			// convert function names to array
			result.put("Referenced by functions", this.functionLinkedByFunctions.get(functionName));
		}

		// TODO: create more fine grained view of function usage (also indirect usage) and validate if referenced functions actually exist
		// load a list of functions that are used by this function (if any)
		if ((functionName != null) && (getFunctionUsesFunctions().containsKey(functionName))) {
			// get referenced function names
			result.put("Uses functions", getFunctionUsesFunctions().get(functionName));
		}

		// now load the code template code
		if (codeTemplateId.contains("_")) {
			// for requesting data from server, the artificial id has to be normalized, first
			codeTemplateId = codeTemplateId.substring(0, codeTemplateId.indexOf('_'));
		}
		String code = getResponseAsXml(connectToRestService("/api/codeTemplates?codeTemplateId=" + codeTemplateId));
		// strip code from meta data
		code = code.substring(code.indexOf("<code>") + 6, code.indexOf("</code>"));
		// and add it to the structure
		result.accumulate("content", code);

		return result;
	}

	/**
	 * Checks if all channels using a function also references the corresponding code template library
	 * 
	 * @param referencingChannelIds
	 *            The IDs of the channels using the function
	 * @param functionName
	 *            The name of the function used by the channels
	 * @return An ordered list of channels that use the function with an indication about if the corresponding code template library is linked
	 *         correctly (green) or not (red). If no channel is using the function, it returns null;
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 * @throws JSONException
	 */
	private Collection<String> generateValidatedLinkedByChannelList(Collection<String> referencingChannelIds, String functionName)
			throws ConfigurationException, ServiceUnavailableException {

		TreeMap<String, String> channels;
		// check if there are any channels for validation
		if ((referencingChannelIds == null) || (referencingChannelIds.size() == 0)) {
			return null;
		}

		String channelName = null, displayName = null, functionId = null, libraryId = null, libraryName = null;
		channels = new TreeMap<String, String>();

		// fetch the id of the code template
		functionId = getCodeTemplateIdByFunctionName(functionName);

		// if function id is null, is means that the Mirth internal configuration is corrupt (code template is there but not referrenced by a library)
		if (functionId == null) {
			// ignore this as it will be fixed w/ next migration
			return null;
		}
		
		// detect the channel group to which the channel belongs
		libraryId = getCodeTemplateLibraryIdByCodeTemplateId().get(functionId);

		// also get the name of the channel group
		libraryName = getCodeTemplateLibraryInfoById(libraryId).getString("Display name");

		// now check for all channels that use the function if they are referencing this library
		for (String channelId : referencingChannelIds) {
			// get the channel
			JSONObject channel = getChannelInfoById(channelId);
			if(channel != null) {
			channelName = channel.getString("Display name");
			} else {
				logger.error("Unable to find channel with id \""+channelId+"\" which references fuction \""+functionName+"\" [library "+libraryName+"]");
			}

			// get the list of code template libraries that are referenced by the channel
			ArrayList<String> libaryReferences = getChannelCodeTemplateLibraryReferences(channelId);
			// if the library is not referenced
			if ((libaryReferences == null) || !libaryReferences.contains(libraryId)) {
				// indicate it to the user and also indicate which code template library has to be added to solve this issue
				displayName = "<font color='Red'><b>" + channelName + "</b></font> [<i><b>add library:</b> " + libraryName + "</i>]";
			} else {
				// library is linked - everything is fine
				displayName = "<font color='Green'><b>" + channelName + "</b></font>";
			}
			// add the channel to the list
			channels.put(channelName, displayName);
		}

		return (channels.size() > 0) ? channels.values() : null;
	}

	/**
	 * Provides the metadata of a specific code template identified by it's id
	 * 
	 * @param codeTemplateId
	 *            The unique code template id
	 * @return The code template meta data
	 * @throws ServiceUnavailableException
	 */
	private JSONObject getCodeTemplateInfoById(String codeTemplateId) throws ServiceUnavailableException {
		return getCodeTemplateInfo().get(codeTemplateId);
	}

	/**
	 * Assembles detail information for a code template library
	 * 
	 * @param libraryId
	 *            The id of the code template library
	 * @param nameOnly
	 *            If this flag is set to true, only the name of the library is provided
	 * @return If the nameOnly-flag is set to true:<br>
	 *         <ul>
	 *         <li><b>Name</b> - The name of the code template library</li>
	 *         </ul>
	 *         If the nameOnly flag is set to false, the following structure:<br>
	 *         <ul>
	 *         <li><b>Name</b> - The name of the code template library</li>
	 *         <li><b>Type</b> - Always <b>Code Template Library</b></li>
	 *         <li><b>Description</b> - A description of the code template library</li>
	 *         <li><b>Version</b> - The version of the code template library</li>
	 *         <li><b>Display date</b> - The date and time of the last modification of the code template library in a human readable format</li>
	 *         <li><b>Last modified</b> - The date and time of the last modification of the code template library in milliseconds</li>
	 *         <li><b>Id</b> - The UUID of the code template library</li>
	 *         <li><b>Number of references</b> - The number of channels that are referencing this library (OPTIONAL)</li>
	 *         <li><b>Referencing channels</b> - A list of channels that use this library. If no channel uses this library, this attribute is not
	 *         present. (OPTIONAL)</li>
	 *         <li><b>Number of invalid references</b> - The number of referencing channel IDs that do not belong (anymore) to any channel
	 *         (OPTIONAL)</li>
	 *         </ul>
	 *         <font color="red"><i>If no corresponding library was found, the function returns <b>null</b></i></font>
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 * 
	 */
	private JSONObject getCodeTemplateLibraryDetails(String libraryId, boolean nameOnly) throws ConfigurationException, ServiceUnavailableException {
		String value;
		JSONObject result = new JSONObject();
		// get the cached information about the code template library
		JSONObject library = getCodeTemplateLibraryInfoById(libraryId);

		if (library == null) {
			// code template library does not exist on this server
			return null;
		}

		// and assemble a new json object that just contains the information needed by the client
		result.accumulate("Name", library.getString("Display name"));

		if (nameOnly) {
			// if just a mapping between component id and component name was
			// needed, the work is done here
			return result;
		}
		// indicate the type
		result.accumulate("Type", "Code Template Library");
		// as description is optional, only add the attribute if there actually is a description
		value = library.getString("Description");
		if (!value.isEmpty()) {
			// the description of the library
			result.accumulate("Description", library.getString("Description"));
		}
		// the version of the library (revision)
		result.accumulate("Version", library.getInt("Version"));
		// the date of the last modification
		result.accumulate("Last modified", library.getLong("Last modified"));
		// the human readable date of the last modification
		result.accumulate("Display date", library.getString("Display date"));
		// the id of the library
		result.accumulate("Id", library.getString("Id"));

		if (library.has("Used by")) {
			TreeSet<String> referencingChannels = new TreeSet<>();
			int invalidReferences = 0;
			// get an ordered list
			for (Object channelId : library.getJSONArray("Used by")) {
				String channelName = getChannelNameById((String) channelId);
				if (channelName != null) {
					// of all valid channel references
					referencingChannels.add(channelName);
				} else {
					// if channel was not found, the reference is invalid
					invalidReferences++;
				}
			}

			// just if there are invalid references
			if (invalidReferences > 0) {
				// add info about them
				result.accumulate("Number of invalid references", invalidReferences);
			}
			// indicate the number of valid channel references
			result.accumulate("Number of references", referencingChannels.size());

			// add ordered list of referencing channel names
			result.accumulate("Referencing channels", referencingChannels.toArray(new String[referencingChannels.size()]));
		}

		return result;
	}

	/**
	 * Provides for an array of container elements (channel group or code template library) the component ids of all children (code templates or
	 * channels)
	 * 
	 * @param groupIds
	 *            The component ids of the channel groups or code template libraries
	 * @param groupType
	 *            the type of the group. Either {@link #CHANNEL_GROUP} or {@link #CODE_TEMPLATE_LIBRARY}
	 * @return A JSON object containing the following information:
	 *         <ul>
	 *         <li><b>success</b> - The status that indicates if the operation was successful (true) or not (false)</li>
	 *         <li><b>statusCode</b> - The HTTP return code (e.g. 200 in case of success)</li>
	 *         <li><b>payload</b> - the actual payload of the request with the following structure:<br/>
	 *         A JSONArray containing all IDs of the children of the groups</li>
	 *         </ul>
	 */
	public NativeObject getContainerChildren(NativeArray groupIds, String groupType) throws JSONException, ServiceUnavailableException {

		JSONObject group = null;
		JSONArray result = new JSONArray();
		// determine attribute names for the members of the provided group type
		String membersTag = (groupType.equals(CHANNEL_GROUP)) ? CHANNEL : CODE_TEMPLATE;
		String membersContainer = (groupType.equals(CHANNEL_GROUP)) ? "channels" : "codeTemplates";

		try {
			// add all children of the provided channel groups or code template libraries
			for (int index = 0; index < groupIds.getLength(); index++) {
				// get the next component from the JavaScript JSON-array
				String groupId = (String) groupIds.get(index, null);

				if (groupId.equals("-")) {
					// no group id means the children of the default channel group are requested. (the default channel group is artificial)
					result = getDefaultGroupChannelIds();
				}

				String groupString = null;

				// try to obtain the group information from the server
				groupString = getComponentFromServer(groupType, groupId);

				// only if the container element (channel group or code template library) was found and has some content
				if (groupString.length() > 30) {
					// access the requested container element
					group = XML.toJSONObject(groupString).getJSONObject("list").getJSONObject(groupType);
					// only if the container element has children (channels or code templates)
					if (!group.get(membersContainer).equals("")) {

						// if a group contains more than one member, the members are arrays (which makes sense)
						if (group.getJSONObject(membersContainer).get(membersTag) instanceof JSONArray) {
							JSONArray member = group.getJSONObject(membersContainer).getJSONArray(membersTag);
							for (int memberIndex = 0; memberIndex < member.length(); memberIndex++) {
								JSONObject component = member.getJSONObject(memberIndex);
								result.put(component.getString("id"));
							}
						} else if (group.getJSONObject(membersContainer).get(membersTag) instanceof JSONObject) {
							// if a group contains only one member, the member is an object
							// (which makes less sense)
							JSONObject member = group.getJSONObject(membersContainer).getJSONObject(membersTag);
							result.put(member.getString("id"));
						}
					}
				}
			}

			return createReturnValue(200, result);
		} catch (ConfigurationException e) {
			// an invalid configuration was detected
			return createReturnValue(500, "Corrupt configuration (either in \"" + configurationFileLocation + "\" or in the definitions of the "
					+ groupType + " or their child components in the Mirth instance \"" + getServer() + "\" itself.");
		} catch (ServiceUnavailableException e) {
			// the target system is not available
			return createReturnValue(503, e.getMessage());
		}
	}

	/**
	 * Provides the source code and metadata of a component from both systems for comparison
	 * 
	 * @param destinationSystem
	 *            The Mirth instance hosting the component to which it should be compared
	 * @param component
	 *            A JSON object with the following structure:
	 *            <ul>
	 *            <li><b>id</b> - The id of the component</li>
	 *            <li><b>type</b> -the type of the component:
	 *            <ul>
	 *            <li>channel</li>
	 *            <li>codeTemplate</li>
	 *            <li>channelGroup</li>
	 *            <li>codeTemplateLibrary</li>
	 *            </ul>
	 *            </li>
	 *            </ul>
	 * @return A JSON object containing the following information:
	 *         <ul>
	 *         <li><b>success</b> - The status that indicates if the operation was successful (true) or not (false)</li>
	 *         <li><b>statusCode</b> - The HTTP return code (e.g. 200 in case of success)</li>
	 *         <li><b>payload</b> - the actual payload of the request with the following structure:<br/>
	 *         <ul>
	 *         <li><b>sourceComponent</b> - The source code of the component at the source system</li>
	 *         <li><b>destinationComponent</b> - The source code of the component at the target system</li>
	 *         <li><b>componentType</b> - The type of the component</li>
	 *         <li><b>metaData</b> -
	 *         <ul>
	 *         <li><b>Name</b> - The component name
	 *         <ul>
	 *         <li><b>source</b> - The component name at the source system</li>
	 *         <li><b>destination</b> - The component name at the target system</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>Last Modified</b> - The last modified date of the component in the format dd.MM.yyyy, HH:mm:ss
	 *         <ul>
	 *         <li><b>source</b> - The last modified date of the component at the source system</li>
	 *         <li><b>destination</b> - The last modified date of the component at the target system</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>Version</b> - The component version
	 *         <ul>
	 *         <li><b>source</b> - The component version at the source system</li>
	 *         <li><b>destination</b> - The component version at the target system</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>Description</b> - The component description
	 *         <ul>
	 *         <li><b>source</b> - The component description at the source system</li>
	 *         <li><b>destination</b> - The component description at the target system</li>
	 *         </ul>
	 *         </li>
	 *         </ul>
	 *         </li>
	 *         </ul>
	 *         </ul>
	 */
	public NativeObject compareComponent(String destinationSystem, NativeObject component) {
		JSONObject sourceComponent = null;
		JSONObject targetComponent = null;

		JSONObject element = null;
		JSONObject result = new JSONObject();
		JSONObject metaData = new JSONObject();

		// extract the type of the component
		String componentType = (String) component.get("type", null);
		// and also it's identifier
		String componentId = (String) component.get("id", null);

		// check if there is a client under the provided system name
		if (!hasClient(destinationSystem)) {
			String message = "Mirth system with name\"" + destinationSystem + "\" is unknown. Please adapt Mirth Migrator configuration.";

			logger.error(message);
			return createReturnValue(500, message);
		}

		try {
			// get the client of the target system
			MirthMigrator targetSystem = null;
			try {
				targetSystem = getClient(destinationSystem);
			} catch (IOException e) {
				return createReturnValue(500,
						"Unable to create a client for the target Mirth instance \"" + destinationSystem + "\": \n" + e.getMessage());
			}

			// get the component name
			String componentName = componentType.equals(CODE_TEMPLATE) ? getCodeTemplateNameById(componentId) : getChannelNameById(componentId);
			// now try to obtain the component from the target Mirth instance
			String targetComponentId = componentType.equals(CODE_TEMPLATE) ? targetSystem.getCodeTemplateIdByName(componentName) : targetSystem.getChannelIdByName(componentName);	

			// get hold of the component at the source system
			try {
				sourceComponent = getComponentDetails(componentType, componentId);
			} catch (ConfigurationException e) {
				// an invalid configuration was detected
				return createReturnValue(500, "Corrupt configuration (either in \"" + configurationFileLocation + "\" or in the " + componentType
						+ " definitions in the Mirth instance \"" + getServer() + "\" itself: \n" + e.getMessage());
			}
			
			try {
				// and also the corresponding component on the target system
				targetComponent = targetSystem.getComponentDetails(componentType, targetComponentId);
			} catch (ConfigurationException e) {
				// an invalid configuration was detected
				return createReturnValue(500, "Corrupt configuration (either in \"" + configurationFileLocation + "\" or in the " + componentType
						+ " definitions in the Mirth instance \"" + destinationSystem + "\" itself: \n" + e.getMessage());
			}

			// first add the source code of the component of the source system
			result.put("sourceContent", sourceComponent.getString("content").replaceAll("&apos;", "'").replaceAll("&quot;", "\"")
					.replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&amp;", "&"));
			// and also the source code of the component of the target system
			result.put("destinationContent", targetComponent.getString("content").replaceAll("&apos;", "'").replaceAll("&quot;", "\"")
					.replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&amp;", "&"));
			// set the type of the component to avoid confusion
			result.put("type", componentType);
			// besides that also some metadata should be compared
			result.put("metaData", metaData);
			// add the component name
			element = new JSONObject();
			element.put("source", sourceComponent.getString("Name"));
			element.put("destination", targetComponent.getString("Name"));
			metaData.put("name", element);
			// the last modified date
			element = new JSONObject();
			element.put("source", sourceComponent.getString("Display date"));
			element.put("destination", targetComponent.getString("Display date"));
			metaData.put("lastModified", element);
			// the version
			element = new JSONObject();
			element.put("source", sourceComponent.getInt("Version"));
			element.put("destination", targetComponent.getInt("Version"));
			metaData.put("version", element);
			// and also the description
			element = new JSONObject();
			element.put("source", sourceComponent.getString("Description"));
			element.put("destination", targetComponent.getString("Description"));
			metaData.put("description", element);

			// obtain the details and return them
			return createReturnValue(200, result);

		} catch (ConfigurationException e) {
			// an invalid configuration was detected
			return createReturnValue(500, "Corrupt configuration (either in \"" + configurationFileLocation + "\" or in the " + componentType
					+ " definitions in the Mirth instance \"" + getServer() + "\" itself: \n" + e.getMessage());
		} catch (ServiceUnavailableException e) {
			// the target system is not available
			return createReturnValue(503, e.getMessage());
		}
	}

	/**
	 * Provides the IDs of all channels that are not assigned to a group
	 * 
	 * @return All IDs of channels that do not belong to a channel group.
	 * @throws ServiceUnavailableException
	 * @throws JSONException
	 */
	private JSONArray getDefaultGroupChannelIds() throws JSONException, ServiceUnavailableException {

		JSONArray result = new JSONArray();

		// retrieve all named channel groups from the mirth instance
		String namedGroups = getChannelGroups();
		HttpURLConnection urlConnection;

		// fetch all channels from the mirth instance
		urlConnection = connectToRestService("/api/channels");
		JSONObject channels = getResponseAsJson(urlConnection);

		// if there are channels in this mirth instance
		if (channels.has(CHANNEL)) {

			// get the channel array (or create it if necessary)
			JSONArray channelArray = (channels.get(CHANNEL) instanceof JSONArray) ? channels.getJSONArray(CHANNEL)
					: (new JSONArray()).put(channels.get(CHANNEL));
			// and check all channels
			for (int index = 0; index < channelArray.length(); index++) {
				// get the next channel
				JSONObject channel = channelArray.getJSONObject(index);
				// and it's id
				String id = channel.getString("id");
				// if it's id is not linked to any named channel group
				if (!namedGroups.contains(id)) {
					// it has to be added to the default group
					result.put(id);
				}

			}

		}

		return result;
	}

	/**
	 * 
	 * @param destinationSystem
	 *            The name of the mirth instance to which the compenents should be migrated
	 * @param components
	 *            A JavaScript JSON Array with objects of the following structure:
	 *            <ul>
	 *            <li><b>id</b> - The id of the component</li>
	 *            <li><b>type</b> - The type of the component (<i>channel</i> or <i>codeTemplate</i>)</li>
	 *            </ul>
	 * @return A list of components that already exist at the target system and thus are causing potential conflicts. There is a special, artificial
	 *         component with the component type application.<br/>
	 *         <br/>
	 *         The return value structure is as follows:
	 * 
	 *         <ul>
	 *         <li><b>success</b> - The status that indicates if the operation was successful (true) or not (false)</li>
	 *         <li><b>statusCode</b> - The HTTP return code (e.g. 200 in case of success)</li>
	 *         <li><b>payload</b> - the actual payload of the request with the following structure
	 *         <ul>
	 *         <li><b>numberOfConflicts</b> - The total of detected conflicts</li>
	 *         <li><b>component</b> - A list of components that are causing (potential) conflicts:</li>
	 *         <ul>
	 *         <li><b>id</b> - The id of the component</li>
	 *         <li><b>type</b> - The type of the component (<i>channel</i>, <i>codeTemplate</i>, or <i>application</i>)<br/>
	 *         The type <i>application</i> is artificial and indicates that the source and target system are running under different Mirth
	 *         versions</li>
	 *         </ul>
	 *         </ul>
	 * 
	 */
	public NativeObject getConflictingComponents(String destinationSystem, NativeArray components) {
		return getConflictingComponents(destinationSystem, components, false);
	}

	/**
	 * 
	 * @param destinationSystem
	 *            The name of the mirth instance to which the compenents should be migrated
	 * @param components
	 *            A JavaScript JSON Array with objects of the following structure:
	 *            <ul>
	 *            <li><b>id</b> - The id of the component</li>
	 *            <li><b>type</b> - The type of the component (<i>channel</i> or <i>codeTemplate</i>)</li>
	 *            </ul>
	 * @param reloadCaches
	 *            If set, the caches of the mirth instances are reloaded before the conflict detection starts in order to assure the latest versions
	 *            from the server are used
	 * @return A list of components that already exist at the target system and thus are causing potential conflicts. There is a special, artificial
	 *         component with the component type application.<br/>
	 *         <br/>
	 *         The return value structure is as follows:
	 * 
	 *         <ul>
	 *         <li><b>success</b> - The status that indicates if the operation was successful (true) or not (false)</li>
	 *         <li><b>statusCode</b> - The HTTP return code (e.g. 200 in case of success)</li>
	 *         <li><b>payload</b> - the actual payload of the request with the following structure
	 *         <ul>
	 *         <li><b>numberOfConflicts</b> - The total of detected conflicts</li>
	 *         <li><b>component</b> - A list of components that are causing (potential) conflicts:</li>
	 *         <ul>
	 *         <li><b>name</b> - The name of the component</li>
	 *         <li><b>id</b> - The id of the component</li>
	 *         <li><b>type</b> - The type of the component (<i>channel</i>, <i>codeTemplate</i>, or <i>application</i>)<br/>
	 *         The type <i>application</i> is artificial and indicates that the source and target system are running under different Mirth
	 *         versions</li>
	 *         </ul>
	 *         </ul>
	 * 
	 */
	public NativeObject getConflictingComponents(String destinationSystem, NativeArray components,
			boolean reloadCaches) {
		JSONObject result = new JSONObject();
		JSONArray conflicts = new JSONArray();
		JSONObject conflict = null;

		result.put("component", conflicts);
		result.put("numberOfConflicts", 0);

		// check if there is a client under the provided system name
		if (!hasClient(destinationSystem)) {
			String message = "Mirth system with name\"" + destinationSystem + "\" is unknown. Please adapt MirthMigrator configuration.";

			logger.error(message);
			return createReturnValue(503, message);
		}

		try {
			// get the client of the target system
			MirthMigrator targetSystem = getClient(destinationSystem);

			if (reloadCaches) {
				if (logger.isDebugEnabled()) {
					logger.debug("Reloading caches of " + getSystemName() + " and " + targetSystem.getSystemName());
				}
				// reload cashes to assure the latest versions of the component are used
				forceRefresh();
				targetSystem.forceRefresh();
			}

			// check if the Mirth version is differing
			if (!getMirthVersion().getVersionString().equals(targetSystem.getMirthVersion().getVersionString())) {

				// indeed, it is. add a conflict for an artificial component
				conflict = new JSONObject();
				conflict.put("id", "00000000-0000-0000-0000-000000000000");
				conflict.put("type", "application");

				// and add it to the conflict list
				conflicts.put(conflict);
			}

			// now check for all channels and/or code templates that should be migrated if they already exist at the target Mirth instance
			for (int index = 0; index < components.getLength(); index++) {
				// get the next component from the JavaScript JSON-array
				NativeObject component = (NativeObject) components.get(index, null);
				// and extract the component id
				String componentId = (String) component.get("id", null);
				// and also the component type (code template or channel)
				String componentType = (String) component.get("type", null);
				// get the component name
				String componentName = componentType.equals(CODE_TEMPLATE) ? getCodeTemplateNameById(componentId) : getChannelNameById(componentId);

				// now try to obtain the component from the target Mirth instance
				String targetComponent = componentType.equals(CODE_TEMPLATE) ? targetSystem.getCodeTemplateIdByName(componentName) : targetSystem.getChannelIdByName(componentName);

				// if the component was found at the target system
				if (targetComponent != null) {
					// add it to the conflicts list
					conflict = new JSONObject();
					conflict.put("name", componentName);
					conflict.put("id", componentId);
					conflict.put("type", componentType);

					// and add it to the conflict list
					conflicts.put(conflict);
				}
			}

			// update the number of conflicts
			result.put("numberOfConflicts", conflicts.length());
		} catch (IOException e) {
			return createReturnValue(500, e.getMessage());
		} catch (ConfigurationException e) {
			// an invalid configuration was detected
			return createReturnValue(500, "Corrupt configuration (either in \"" + configurationFileLocation
					+ "\" or in the component definitions in the Mirth instance \"" + getServer() + "\" itself.");
		} catch (ServiceUnavailableException e) {
			// the target system is not available
			return createReturnValue(503, e.getMessage());
		}

		return createReturnValue(200, result);
	}

	/**
	 * Provides a list of referenced code templates for a list of channels
	 * 
	 * @param channels
	 *            A JavaScript JSON Array with objects of the following structure:
	 *            <ul>
	 *            <li><b>id</b> - The id of the component</li>
	 *            <li><b>type</b> - The type of the component (<i>channel</i> or <i>codeTemplate</i> whereas codeTemplate entries will be ignored,
	 *            here)</li>
	 *            </ul>
	 * @return A list of referenced code templates with the following structure:
	 *         <ul>
	 *         <li><b>name</b> - The name of the code template</li>
	 *         <li><b>id</b> - The id of the code template</li>
	 *         <li><b>type</b> - <i>codeTemplate</i></li>
	 *         </ul>
	 */
	public NativeObject getReferencedCodeTemplates(NativeArray channels) {

		HashMap<String, JSONObject> referencedCodeTemplates = new HashMap<String, JSONObject>();
		HashSet<String> functionReferences = new HashSet<String>();

		try {
			for (int index = 0; index < channels.getLength(); index++) {
				// get the next component from the JavaScript JSON-array
				NativeObject channel = (NativeObject) channels.get(index, null);

				// and also the component type (code template or channel)
				String componentType = (String) channel.get("type", null);
				// in case of a channel
				if (componentType.equals("channel")) {
					// get it's id
					String channelId = (String) channel.get("id", null);
					// retrieve via the channel id the list of referenced functions
					ArrayList<String> referencedFunctions = getChannelFunctionReferences(channelId);
					// if there are any function references
					if (referencedFunctions != null) {
						// get the code template that belongs to the function
						for (String referencedFunction : referencedFunctions) {
							// detect all indirect references caused by this function (this will detect also the function itself)
							functionReferences = detectReferencedFunctions(referencedFunction, functionReferences);
						}

						// now find the code template to which the functions belong
						for (String function : functionReferences) {
							// get the id of the code template containing the function
							String codeTemplateId = getCodeTemplateIdByFunctionName(function);
							// if the code template is not yet in the references list
							if (!referencedCodeTemplates.containsKey(codeTemplateId)) {
								JSONObject codeTemplate = getCodeTemplateInfoById(codeTemplateId);
								if(codeTemplate != null) {
								String codeTemplateName = codeTemplate.getString("Display name");
								// create a new entry
								JSONObject newElement = new JSONObject();
								newElement.put("name", codeTemplateName);
								newElement.put("id", codeTemplateId);
								newElement.put("type", "codeTemplate");
								// and add it to the parameter list
								referencedCodeTemplates.put(codeTemplateId, newElement);
								}
							}
						}
					}
				}
			}
		} catch (ConfigurationException e) {
			// an invalid configuration was detected
			return createReturnValue(500, "Corrupt configuration (either in \"" + configurationFileLocation
					+ "\" or in the component definitions in the Mirth instance \"" + getServer() + "\" itself.");
		} catch (ServiceUnavailableException e) {
			// the target system is not available
			return createReturnValue(503, e.getMessage());
		}

		return createReturnValue(200, new JSONArray(referencedCodeTemplates.values()));
	}

	/**
	 * Obtains a list of directly and indirectly referenced functions for a function 
	 * @param functionName The name of the function for which direct and indirect references should be detected. (function name format is like <i>myFunction()</i>)
	 * @param detectedReferences The list of references (including the function itself)
	 * @return A list of referenced functions
	 * @throws ServiceUnavailableException 
	 */
	private HashSet<String> detectReferencedFunctions(String functionName, HashSet<String> detectedReferences) throws ServiceUnavailableException {
		if (detectedReferences == null) {
			detectedReferences = new HashSet<String>();
		}
		// if this function is not yet in the references list
		if (!detectedReferences.contains(functionName)) {
			// add it
			detectedReferences.add(functionName);
			// if this function references other functions
			if (getFunctionUsesFunctions().containsKey(functionName)) {
				// get a list of functions referenced by this function
				ArrayList<String> referencedFunctions = getFunctionUsesFunctions().get(functionName);
				// and check every referenced function
				for (String referencedFunction : referencedFunctions) {
					// for further references
					detectedReferences = detectReferencedFunctions(referencedFunction, detectedReferences);
				}
			}
		}

		return detectedReferences;
	}
	
	
	
	/**
	 * Provides for a given function name the id of the code template in which the function is defined
	 * 
	 * @param functionName
	 *            The name of the function followed by brackets e.g. myFunction()
	 * @return The id of the corresponding code template
	 * @throws ServiceUnavailableException
	 */
	private String getCodeTemplateIdByFunctionName(String functionName) throws ServiceUnavailableException {
		// if the function name to code template id mapping is not yet loaded
		if (this.codeTemplateIdByFunctionName == null) {
			// analyze code template libraries
			getCodeTemplateLibraryInfo();
		}

		// and return the wanted mapping
		return this.codeTemplateIdByFunctionName.get(functionName);
	}
	
	/**
	 * Migrates components of this system to a target system
	 * 
	 * @param destinationSystem
	 *            The target system to which the components should be migrated
	 * @param components
	 *            A JavaScript JSON Array with objects of the following structure:
	 *            <ul>
	 *            <li><b>id</b> - The id of the component</li>
	 *            <li><b>type</b> - The type of the component (<i>channel</i> or <i>codeTemplate</i>)</li>
	 *            </ul>
	 * @return A migration report with the following structure:
	 * 
	 *         <ul>
	 *         <li><b>success</b> - The status that indicates if the operation was successful (true) or not (false)</li>
	 *         <li><b>statusCode</b> - The HTTP return code (e.g. 200 in case of success)</li>
	 *         <li><b>payload</b> - the actual payload of the request with the following structure
	 *         <ul>
	 *         <li><b>success</b> - a JSON Array of successfully migrated components. Each entry contains the following attributes:
	 *         <ul>
	 *         <li><b>name</b> - the name of the component</li>
	 *         <li><b>id</b> - the id of the component</li>
	 *         <li><b>type</b> - the type of the component. It might be:
	 *         <ul>
	 *         <li><b>channel</b> - the channels themselves</li>
	 *         <li><b>channelGroup</b> - the containers of the migrated channels</li>
	 *         <li><b>codeTemplateLibrary</b> - needed for the code template library dependencies</li>
	 *         <li><b>channelTag</b> - the tags assigned to the migrated channels</li>
	 *         <li><b>channelPruning</b> - the adaption of the pruning information for the migrated channels</li>
	 *         </ul>
	 *         </li>
	 *         </ul>
	 *         </li>
	 *         <li><b>failure</b> - a JSON Array of components that failed the migration. Each entry contains the following attributes:
	 *         <ul>
	 *         <li><b>name</b> - the name of the component</li>
	 *         <li><b>id</b> - the id of the component</li>
	 *         <li><b>type</b> - the type of the component. It might be:
	 *         <ul>
	 *         <li><b>channel</b> - the channels themselves</li>
	 *         <li><b>channelGroup</b> - the containers of the migrated channels</li>
	 *         <li><b>codeTemplateLibrary</b> - needed for the code template library dependencies</li>
	 *         <li><b>channelTag</b> - the tags assigned to the migrated channels</li>
	 *         <li><b>channelPruning</b> - the adaption of the pruning information for the migrated channels</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>headers</b> - all headers of the update request <i>(not for IO Exception)</i></li>
	 *         <li><b>configuration</b> - the altered code template configuration in the format of the target system</li>
	 *         <li><b>errorCode</b> - the HTTP error code <i>(not for IO Exception)</i></li>
	 *         <li><b>errorMessage</b> - a more detailed error message</li>
	 *         </ul>
	 *         </li>
	 *         </ul>
	 */
	public NativeObject migrateComponents(String destinationSystem, NativeArray components) {
		ArrayList<String> channelsToMigrate = new ArrayList<String>();
		ArrayList<String> codeTemplatesToMigrate = new ArrayList<String>();
		JSONObject migrationReport = null;

		// check if there is a client under the provided system name
		if (!hasClient(destinationSystem)) {
			String message = "Mirth system with name\"" + destinationSystem + "\" is unknown. Please adapt MirthMigrator configuration.";

			logger.error(message);
			return createReturnValue(503, message);
		}

		try {
			// get the client of the target system
			MirthMigrator targetSystem = getClient(destinationSystem);

			// separate code templates and channels as code templates must be migrated first (as they are referenced by channels)
			for (int index = 0; index < components.getLength(); index++) {
				// get the next component from the JavaScript JSON-array
				NativeObject component = (NativeObject) components.get(index, null);
				// and extract the component id
				String componentId = (String) component.get("id", null);
				// and also the component type (code template or channel)
				String componentType = (String) component.get("type", null);
				// depending on it's type add the component id
				if (componentType.equals("channel")) {
					// either to the channel list
					channelsToMigrate.add(componentId);
				} else {
					// or to the code template list
					codeTemplatesToMigrate.add(componentId);
				}
			}

			// if there are any code templates to migrate
			if (!codeTemplatesToMigrate.isEmpty()) {
				// do the job
				migrationReport = migrateCodeTemplates(targetSystem, codeTemplatesToMigrate.toArray(new String[0]));
			}

			// if there are any channels to migrate
			if (!channelsToMigrate.isEmpty()) {
				// do the job
				JSONObject result = migrateChannels(targetSystem, channelsToMigrate.toArray(new String[0]));
				if (migrationReport != null) {
					// merge reports of successfully migrated components
					migrationReport.getJSONArray("success").putAll(result.getJSONArray("success"));
					// merge reports of failed migrated efforts
					migrationReport.getJSONArray("failure").putAll(result.getJSONArray("failure"));
				} else {
					// there is nothing else - thus the result can be used unaltered
					migrationReport = result;
				}
			}

			return createReturnValue(500, migrationReport);

		} catch (IOException e) {
			return createReturnValue(500, e.getMessage());
		} catch (ConfigurationException e) {
			// an invalid configuration was detected
			return createReturnValue(500, "Corrupt configuration (either in \"" + configurationFileLocation
					+ "\" or in the component definitions in the Mirth instance \"" + getServer() + "\" itself.");
		} catch (ServiceUnavailableException e) {
			// the target system is not available
			return createReturnValue(503, e.getMessage());
		}
	}

	/**
	 * Reloads the content (code templates, channels, code template libraries, channel groups) and their dependencies
	 * 
	 * @param systemName
	 *            The name of the Mirth instance that should be reloaded
	 * @throws IOException
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 * 
	 */
	public static void reloadClient(String systemName) throws IOException, ConfigurationException, ServiceUnavailableException {
		getClient(systemName).forceRefresh();
	}

	/**
	 * Reloads the content (code templates, channels, code template libraries, channel groups) and their dependencies
	 * 
	 * @param systemNames
	 *            A JavaScript JSON array containing the names of the Mirth instances that should be reloaded
	 * 
	 * @throws IOException
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 * 
	 */
	public static void reloadClient(NativeArray systemNames) throws IOException, ConfigurationException, ServiceUnavailableException {
		// refresh all listed Mirth instance instances
		for (int index = 0; index < systemNames.getLength(); index++) {
			// get the name of the next Mirth instance
			String systemName = (String) systemNames.get(index, null);
			getClient(systemName).forceRefresh();
		}
	}

	/**
	 * Provides details about the potential migration conflicts of a Mirth component
	 * 
	 * @param destinationSystem
	 *            The name of the Mirth instance to which the component should be migrated.
	 * @param component
	 *            A JSON object with the following structure:
	 *            <ul>
	 *            <li><b>id</b> - The id of the component</li>
	 *            <li><b>type</b> -the type of the component:
	 *            <ul>
	 *            <li>channel</li>
	 *            <li>codeTemplate</li>
	 *            <li>channelGroup</li>
	 *            <li>codeTemplateLibrary</li>
	 *            </ul>
	 *            </li>
	 *            </ul>
	 * @return A JSON object with the following structure:
	 *         <ul>
	 *         <li><b>success</b> - The status that indicates if the operation was successful (true) or not (false)</li>
	 *         <li><b>statusCode</b> - The HTTP return code (e.g. 200 in case of success)</li>
	 *         <li><b>payload</b> - the actual payload of the request with the following structure:
	 *         <ul>
	 *         <li><b>componentType</b> - the type of the component:
	 *         <ul>
	 *         <li>channel</li>
	 *         <li>codeTemplate</li>
	 *         <li>channelGroup</li>
	 *         <li>codeTemplateLibrary</li>
	 *         <li>application</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>componentId</b> - The id of the component</li>
	 *         <li><b>componentName</b> - The name of the component (<i>OPTIONAL</i>)<br/>
	 *         <i>Not available for type application</i></li>
	 *         <li><b>metaData</b> - Some meta information about the component at the source and target system. (<i>OPTIONAL</i>)<br/>
	 *         <i>Not available for type application
	 *         <ul>
	 *         <li><b>name</b> - the name of the component
	 *         <ul>
	 *         <li><b>source</b> - the name at the source Mirth instance</li>
	 *         <li><b>destination</b> - the name at the target Mirth instance</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>lastModified</b> - the last modified date of the component
	 *         <ul>
	 *         <li><b>source</b> - the last modified date at the source Mirth instance</li>
	 *         <li><b>destination</b> - the last modified date at the target Mirth instance</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>version</b> - the revision of the component
	 *         <ul>
	 *         <li><b>source</b> - the revision at the source Mirth instance</li>
	 *         <li><b>destination</b> - the revision at the target Mirth instance</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>description</b> - the description of the component (<i>OPTIONAL</i>)
	 *         <ul>
	 *         <li><b>source</b> - the description at the source Mirth instance</li>
	 *         <li><b>destination</b> - the description at the target Mirth instance</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>parameters</b> - the parameters of the component. (<i>OPTIONAL</i>)<br/>
	 *         <i>This is only used for functions</i>
	 *         <ul>
	 *         <li><b>source</b> - the parameters at the source Mirth instance</li>
	 *         <li><b>destination</b> - the parameters at the target Mirth instance</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>returnValue</b> - the return value of the component (<i>OPTIONAL</i>)<br/>
	 *         <i>This is only used for functions</i>
	 *         <ul>
	 *         <li><b>source</b> - the return value at the source Mirth instance</li>
	 *         <li><b>destination</b> - the return value at the target Mirth instance</li>
	 *         </ul>
	 *         </li>
	 *         </ul>
	 *         </li>
	 *         <li><b>conflicts</b> - A list of conflicts that have been detected for the component. Every entry has the following structure:
	 *         <ul>
	 *         <li><b>conflictMessage</b> - A description of the conflict</li>
	 *         <li><b>conflictType</b> - A short description of the conflict</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>sourceContent</b> - The component definition of the source System</i>
	 *         <li><b>destinationContent</b> - The component definition of the target System</i>
	 *         <li><b>numberOfConflicts</b> - The total number of detected (potential) conflicts</i>
	 *         </ul>
	 *         </i>
	 *         </ul>
	 *         If the request was not successful (success = false), the payload usually only consists of an error message
	 */
	public NativeObject getConflicts(String destinationSystem, NativeObject component)
			throws JSONException, FileNotFoundException, IOException, ParseException, ConfigurationException, ServiceUnavailableException {
		return getConflicts(destinationSystem, component, false);
	}

	/**
	 * Provides details about the potential migration conflicts of a Mirth component
	 * 
	 * @param destinationSystem
	 *            The name of the Mirth instance to which the component should be migrated.
	 * @param reloadCaches
	 *            If set, the caches of the mirth instances are reloaded before the conflict detection starts in order to assure the latest versions
	 *            from the server are used
	 * @param component
	 *            A JSON object with the following structure:
	 *            <ul>
	 *            <li><b>id</b> - The id of the component</li>
	 *            <li><b>type</b> -the type of the component:
	 *            <ul>
	 *            <li>channel</li>
	 *            <li>codeTemplate</li>
	 *            <li>channelGroup</li>
	 *            <li>codeTemplateLibrary</li>
	 *            </ul>
	 *            </li>
	 *            </ul>
	 * @return A JSON object with the following structure:
	 *         <ul>
	 *         <li><b>success</b> - The status that indicates if the operation was successful (true) or not (false)</li>
	 *         <li><b>statusCode</b> - The HTTP return code (e.g. 200 in case of success)</li>
	 *         <li><b>payload</b> - the actual payload of the request with the following structure:
	 *         <ul>
	 *         <li><b>componentType</b> - the type of the component:
	 *         <ul>
	 *         <li>channel</li>
	 *         <li>codeTemplate</li>
	 *         <li>channelGroup</li>
	 *         <li>codeTemplateLibrary</li>
	 *         <li>application</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>componentId</b> - The id of the component</li>
	 *         <li><b>componentName</b> - The name of the component (<i>OPTIONAL</i>)<br/>
	 *         <i>Not available for type application</i></li>
	 *         <li><b>metaData</b> - Some meta information about the component at the source and target system. (<i>OPTIONAL</i>)<br/>
	 *         <i>Not available for type application
	 *         <ul>
	 *         <li><b>name</b> - the name of the component
	 *         <ul>
	 *         <li><b>source</b> - the name at the source Mirth instance</li>
	 *         <li><b>destination</b> - the name at the target Mirth instance</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>lastModified</b> - the last modified date of the component
	 *         <ul>
	 *         <li><b>source</b> - the last modified date at the source Mirth instance</li>
	 *         <li><b>destination</b> - the last modified date at the target Mirth instance</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>version</b> - the revision of the component
	 *         <ul>
	 *         <li><b>source</b> - the revision at the source Mirth instance</li>
	 *         <li><b>destination</b> - the revision at the target Mirth instance</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>description</b> - the description of the component (<i>OPTIONAL</i>)
	 *         <ul>
	 *         <li><b>source</b> - the description at the source Mirth instance</li>
	 *         <li><b>destination</b> - the description at the target Mirth instance</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>parameters</b> - the parameters of the component. (<i>OPTIONAL</i>)<br/>
	 *         <i>This is only used for functions</i>
	 *         <ul>
	 *         <li><b>source</b> - the parameters at the source Mirth instance</li>
	 *         <li><b>destination</b> - the parameters at the target Mirth instance</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>returnValue</b> - the return value of the component (<i>OPTIONAL</i>)<br/>
	 *         <i>This is only used for functions</i>
	 *         <ul>
	 *         <li><b>source</b> - the return value at the source Mirth instance</li>
	 *         <li><b>destination</b> - the return value at the target Mirth instance</li>
	 *         </ul>
	 *         </li>
	 *         </ul>
	 *         </li>
	 *         <li><b>conflicts</b> - A list of conflicts that have been detected for the component. Every entry has the following structure:
	 *         <ul>
	 *         <li><b>conflictMessage</b> - A description of the conflict</li>
	 *         <li><b>conflictType</b> - A short description of the conflict</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>sourceContent</b> - The component definition of the source System</i>
	 *         <li><b>destinationContent</b> - The component definition of the target System</i>
	 *         <li><b>numberOfConflicts</b> - The total number of detected (potential) conflicts</i>
	 *         </ul>
	 *         </i>
	 *         </ul>
	 *         If the request was not successful (success = false), the payload usually only consists of an error message
	 */
	public NativeObject getConflicts(String destinationSystem, NativeObject component, boolean reloadCaches) {

		JSONObject result = new JSONObject();
		String componentName = null;
		String targetComponentId = null;
		JSONObject item = null;
		
		// extract the component id
		String componentId = (String) component.get("id", null);
		// and also the component type
		String componentType = ((String) component.get("type", null));

		try {
			// create the basic JSON structure
			result.put("type", componentType);
			result.put("id", componentId);
			result.put("conflicts", new JSONArray());
			result.put("numberOfConflicts", 0);

			// check if there is a client under the provided system name
			if (!hasClient(destinationSystem)) {
				logger.error("Mirth system with name\"" + destinationSystem + "\" is unknown. Please adapt MirthMigrator configuration.");
				return createReturnValue(500,
						"Mirth system with name\"" + destinationSystem + "\" is unknown. Please adapt MirthMigrator configuration.");
			}

			// get the client of the target system
			MirthMigrator targetSystem = getClient(destinationSystem);

			// if the type is application, the (currently) only possible conflict is a version conflict
			if (componentType.equals("application")) {
				// create a new conflict
				item = new JSONObject();
				// and add it to the conflicts list
				result.append("conflicts", item);
				result.put("numberOfConflicts", 1);

				// fill in the details
				item.put("conflictMessage", "The Mirth version of the source system differs from the Mirth version of the destination system.");
				item.put("conflictType", "mirth version conflict");
				item.put("sourceMirthVersion", getMirthVersion().getVersionString());
				item.put("destinationMirthVersion", targetSystem.getMirthVersion().getVersionString());

				// and stop processing
				return createReturnValue(200, result);
			}

			if (reloadCaches) {
				if (logger.isDebugEnabled()) {
					logger.debug("Reloading caches of " + getSystemName() + " and " + targetSystem.getSystemName());
				}
				// reload cashes to assure the latest versions of the component are used
				forceRefresh();
				targetSystem.forceRefresh();
			}
			
			// determine the id of the target component
			if(componentType.equals(CODE_TEMPLATE)) {
				//  get the name of the source code template
				componentName = getCodeTemplateNameById(componentId);
				// use the name to determine the id of the corresponding target code template
				targetComponentId = targetSystem.getCodeTemplateIdByName(componentName);
			} else {
				//  get the name of the source code template
				componentName = getChannelNameById(componentId);
				// use the name to determine the id of the corresponding target code template
				targetComponentId = targetSystem.getChannelIdByName(componentName);				
			}
			
			// get the component information from the source system
			JSONObject sourceComponent = getComponentDetails(componentType, componentId);
			// and also from the target system
			JSONObject targetComponent = targetSystem.getComponentDetails(componentType, targetComponentId);

			// if one (or both) of the components does not exist
			if ((sourceComponent == null) || (targetComponent == null)) {
				// no component, no conflict
				return createReturnValue(200, result);
			}

			result.put("name", sourceComponent.getString("Name"));
			JSONObject metaData = new JSONObject();
			result.put("metaData", metaData);
			JSONArray conflicts = new JSONArray();
			result.put("conflicts", conflicts);

			// add the component name
			item = new JSONObject();
			item.put("source", sourceComponent.getString("Name"));
			item.put("destination", targetComponent.getString("Name"));
			metaData.put("name", item);

			// add the component id
			item = new JSONObject();
			item.put("source", componentId);
			item.put("destination", targetComponentId);
			metaData.put("id", item);

			// the last modified date
			item = new JSONObject();
			item.put("source", sourceComponent.has("Display date") ? sourceComponent.getString("Display date") : "-");
			item.put("destination", targetComponent.has("Display date") ? targetComponent.getString("Display date") : "-");
			metaData.put("lastModified", item);

			// and the revision number
			item = new JSONObject();
			item.put("source", sourceComponent.getInt("Version"));
			item.put("destination", targetComponent.getInt("Version"));
			metaData.put("version", item);

			// if there is a description
			if (sourceComponent.has("Description") || targetComponent.has("Description")) {
				item = new JSONObject();
				item.put("source", sourceComponent.has("Description") ? sourceComponent.getString("Description") : '-');
				item.put("destination", targetComponent.has("Description") ? targetComponent.getString("Description") : '-');
				metaData.put("description", item);
			}

			// if there is are parameters (it's only the case for functions from code templates)
			if (sourceComponent.has("Parameters") || targetComponent.has("Parameters")) {
				item = new JSONObject();
				item.put("source", sourceComponent.has("Parameters") ? sourceComponent.getString("Parameters") : '-');
				item.put("destination", targetComponent.has("Parameters") ? targetComponent.getString("Parameters") : '-');
				metaData.put("parameters", item);
			}

			// if there is a return value (it's only the case for functions from code templates)
			if (sourceComponent.has("Return value") || targetComponent.has("Return value")) {
				item = new JSONObject();
				item.put("source", sourceComponent.has("Return value") ? sourceComponent.getString("Return value") : '-');
				item.put("destination", targetComponent.has("Return value") ? targetComponent.getString("Return value") : '-');
				metaData.put("returnValue", item);
			}

			// check the component version at the target Mirth instance is newer than the one that is about to be migrated
			if (sourceComponent.has("Last modified") && targetComponent.has("Last modified")
					&& (sourceComponent.getLong("Last modified") < targetComponent.getLong("Last modified"))) {

				// target version is newer
				item = new JSONObject();
				item.put("conflictMessage",
						"The component already exists with a newer modification date: " + targetComponent.getString("Display date"));
				item.put("conflictType", "older modification date");
				conflicts.put(item);
			}

			// check if component names are differing
			if (!componentId.equals(targetComponentId)) {

				// the target component has another name
				item = new JSONObject();
				item.put("conflictMessage", "The id of the source component differs from the id of the destination component (this is usually ok)");
				item.put("conflictType", "different ids");
				conflicts.put(item);
			}

			// ToDo: IT MIGHT BE WISE TO ALSO ADD A CHECK FOR COMPONENTS WITH SAME NAME BUT DIFFERENT ID

			// also add the component source code if present
			if (sourceComponent.has("content") && targetComponent.has("content")) {
				// add the decoded source component
				result.put("sourceContent", sourceComponent.getString("content").replaceAll("&apos;", "'").replaceAll("&quot;", "\"")
				.replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&amp;", "&"));
				// and destination component source code
				result.put("destinationContent", targetComponent.getString("content").replaceAll("&apos;", "'").replaceAll("&quot;", "\"")
				.replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&amp;", "&"));
			}

			// and also indicate the total number of detected conflicts
			result.put("numberOfConflicts", conflicts.length());

			return createReturnValue(200, result);
		} catch (ServiceUnavailableException e) {
			// the target system is not available
			return createReturnValue(503, e.getMessage());
		} catch (ConfigurationException e) {
			// an invalid configuration was detected
			return createReturnValue(500, "Corrupt configuration (either in \"" + configurationFileLocation + "\" or in the definition of the "
					+ componentType + " " + componentId + " or its related elements themselves.");
		} catch (IOException e) {

			return createReturnValue(500, e.getMessage());
		}
	}

	/**
	 * Assembles a standardized structure for an API return value
	 * 
	 * @param code
	 *            The HTTP return code (e.g. 200 in case of success)
	 * @param payload
	 *            The actual payload the has been generated for the request. If the request was not successful, it usually contains an error message
	 * @return A JSONObject with the following structure:
	 *         <ul>
	 *         <li><b>success</b> - The status that indicates if the operation was successful (true) or not (false)</li>
	 *         <li><b>statusCode</b> - The HTTP return code (e.g. 200 in case of success)</li>
	 *         <li><b>payload</b> - The actual payload the has been generated for the request. If the request was not successful, it usually contains
	 *         an error message</li>
	 *         </ul>
	 */
	private static NativeObject createReturnValue(int code, Object payload) {
		NativeObject javascriptJson = null;

		JSONObject returnValue = new JSONObject();
		// everything in the range below 300 is considered as successful
		returnValue.put("success", (code < 300));
		returnValue.put("statusCode", code);
		returnValue.put("payload", payload);

		try {

			// and return it a JavasScript JSON object
			javascriptJson = (NativeObject) jsonParser.parseValue(returnValue.toString());
		} catch (ParseException e) {
			logger.error("FAILURE: It seems that the JSON could NOT successfully be parsed to JavaScript! \n" + returnValue.toString());
		}

		return javascriptJson;
	}

	/**
	 * Retrieves a component from the server
	 * 
	 * @param componentType
	 *            {@link #CHANNEL_GROUP}, {@link #CHANNEL}, {@link #CODE_TEMPLATE_LIBRARY}, or {@link #CODE_TEMPLATE}
	 * @param componentId
	 *            The unique id of the component
	 * @return The component as a String.
	 * @throws ConfigurationException
	 *             If component information could not be retrieved
	 * @throws ServiceUnavailableException
	 */
	private String getComponentFromServer(String componentType, String componentId) throws ConfigurationException, ServiceUnavailableException {
		HttpURLConnection restCall;
		String serviceUrl;

		// assemble the URL for the rest call
		switch (componentType) {
		case CHANNEL:
			serviceUrl = "/api/channels?channelId=" + componentId;
			break;
		case CHANNEL_GROUP:
			serviceUrl = "/api/channelgroups?channelGroupId=" + componentId;
			break;
		case CODE_TEMPLATE:
			serviceUrl = "/api/codeTemplates?codeTemplateId=" + componentId;
			break;
		case CODE_TEMPLATE_LIBRARY:
			serviceUrl = "/api/codeTemplateLibraries?libraryId=" + componentId + "&includeCodeTemplates=false";
			break;
		default:
			throw new ConfigurationException("Unsupported component type \"" + componentType + "\" has been specified.");
		}

		restCall = connectToRestService(serviceUrl);
		return getResponseAsXml(restCall);
	}

	/**
	 * Sends a request to a REST service
	 * 
	 * @param serviceEndpoint
	 *            The remaining URL details after server_port/.. in most cases /api/<component_Type>
	 * @return The opened HTTPUrlConnection.
	 */
	private HttpURLConnection connectToRestService(String serviceEndpoint) {
		return connectToRestService(getServer(), getPort(), serviceEndpoint);
	}

	/**
	 * Sends a request to a REST service
	 * 
	 * @param serviceEndpoint
	 *            The remaining URL details after server_port/.. in most cases /api/<component_Type>
	 * @param serverName
	 *            The mirth server name
	 * @param Port
	 *            The mirth service port
	 * @return The opened HTTPUrlConnection.
	 */
	private static HttpURLConnection connectToRestService(String serverName, int Port, String serviceEndpoint) {
		URL url = null;
		try {
			// assemble the URL
			url = new URL("https://" + serverName + ":" + Port + serviceEndpoint);
			// and open the connection
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			// connection should be established w/i 5 seconds
			connection.setConnectTimeout(5000);
			if (logger.isDebugEnabled()) {
				logger.debug("connection has been opened to " + url);
			}
			// newer mirth services need this header. So assure that it is present
			connection.setRequestProperty("X-Requested-With", MirthMigrator.clientIdentifier);
			connection.setRequestProperty("Content-Type", "application/xml");
			connection.setRequestProperty("Cache-Control", "no-cache");
			connection.setRequestProperty("accept", "application/xml");
			connection.setRequestProperty("Accept-Charset", "UTF-8");
			return connection;
		} catch (SocketTimeoutException ste) {
			logger.error("Webservice \"" + url + "\" is currently not available: " + ste.getMessage());
			return null;
		} catch (IOException e) {
			logger.error("Connection Exception (connectToRestService()) to \"" + url + "\": " + e.getMessage());
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Reads the server response
	 * 
	 * @param connection
	 *            The http connection from which the response should be read
	 * @return A JSON object containing the following attributes:
	 *         <ul>
	 *         <li><b>responseCode</b> - The HTTP response code</li>
	 *         <li><b>successful</b> - true if the action was successful, false otherwise</li>
	 *         <li><b>responseMessage</b> - The response from the server, if the communication was successful. Otherwise the error message</li>
	 *         </ul>
	 * @throws ServiceUnavailableException
	 *             If the Mirth instance is not available
	 * @throws IOException
	 *             If stream could not be read
	 */
	private static JSONObject readResponse(HttpURLConnection connection) throws ServiceUnavailableException {
		InputStream response = null;
		JSONObject result = new JSONObject();
		int responseCode = -1;

		try {
			// if everything went right read the response from the regular stream
			response = connection.getInputStream();
		} catch (Exception exception) {

			// Oops, it didn't. So use the error stream, instead
			response = connection.getErrorStream();
		}

		try {
			// if no valid connection is available
			responseCode = connection.getResponseCode();

			// this usually means no valid session and is e.g. the case if the service had been restarted
			if (responseCode == 401) {
				String message = "Response stream is not available - re-login is needed (" + responseCode + ")";
				connection.disconnect();		
				logger.error(message);
				result.put("responseCode", 400);
				result.put("successful", false);
				result.put("responseMessage", message);

				return result;
			}
			if (responseCode == 403) {
				String message = "Mirth service explicitly rejected the request (" + responseCode + ")";

				logger.error(message);

				result.put("responseCode", 403);
				result.put("successful", false);
				result.put("responseMessage", message);

				return result;
			}
		} catch (IOException e) {
			// neither the service is available
			/*
			 * String message = "Service " + connection.getURL().getHost() + ":" + connection.getURL().getPort() + " is not available";
			 * 
			 * root.error(message);
			 * 
			 * result.put("responseCode", 503); result.put("successful", false); result.put("responseMessage", message);
			 * 
			 * return result;
			 */
			throw new ServiceUnavailableException(
					String.format("Service at %s:%d is currently not available", connection.getURL().getHost(), connection.getURL().getPort()));
		}

		// read everything in
		BufferedReader reader;
		reader = new BufferedReader(new InputStreamReader(response, StandardCharsets.UTF_8));

		StringBuilder sb = new StringBuilder();
		String line;
		try {
			while ((line = reader.readLine()) != null) {
				sb.append(line).append('\n');
			}
			reader.close();

			// assemble the response and return it
			result.put("responseCode", connection.getResponseCode());
			result.put("successful", true);
			result.put("responseMessage", sb.toString());

			return result;
		} catch (IOException e) {
			logger.error("readResponse() IOException: " + e.getMessage());
			return null;
		} finally {
			try {
				reader.close();
			} catch (Exception ex) {
				logger.error("readResponse() failure: " + ex.getMessage());
			}
		}
	}

	/**
	 * Retrieves the response of a rest request in xml format
	 * 
	 * @param restService
	 *            The connection to the rest service
	 * @return The response in xml format or an empty string if no result could be retrieved
	 * @throws ServiceUnavailableException
	 * @throws IOException
	 */
	private String getResponseAsXml(HttpURLConnection restService) throws ServiceUnavailableException {

		// try to execute the query
		JSONObject response = getResponseAsXml(restService, getServerSessionCookie());

		if (response.getInt("responseCode") == 400) {

			// if re-login was successful
			if (createServerSession()) {
				restService.disconnect();
				String serviceUrl = restService.getURL().getPath();
				// connection needs to be reestablished
				restService = connectToRestService(serviceUrl);
				// try to re-execute the query
				response = getResponseAsXml(restService, getServerSessionCookie());
				// all caches need to be refilled as current state of the server is unknown
				forceRefresh();
			}
		}

		return response.getBoolean("successful") ? response.getString("responseMessage") : "";
	}

	/**
	 * Retrieves the response of the rest request in xml format
	 * 
	 * @param restService
	 *            The connection to the rest service
	 * @param sessionCookie
	 *            The cookie that identifies an active session
	 * @return A JSON object containing the following attributes:
	 *         <ul>
	 *         <li><b>responseCode</b> - The HTTP response code</li>
	 *         <li><b>successful</b> - true if the action was successful, false otherwise</li>
	 *         <li><b>responseMessage</b> - The response from the server, if the communication was successful. Otherwise the error message</li>
	 *         </ul>
	 * @throws IOException
	 * @throws ServiceUnavailableException
	 *             If the Mirth instance is not available
	 * @throws ProtocolException
	 */
	private static JSONObject getResponseAsXml(HttpURLConnection restService, String sessionCookie) throws ServiceUnavailableException {

		try {
			// no worries this method is correct
			restService.setRequestMethod("GET");
			restService.setRequestProperty("Accept", "application/xml");
			restService.setRequestProperty("X-Requested-With", MirthMigrator.clientIdentifier);
			restService.setRequestProperty("Cookie", sessionCookie);
		} catch (ProtocolException e) {
		} catch (IllegalStateException e2) {
			// this exception is thrown if the server was unavailable before and a re-login had to be issued.
			// As in this case the
		}

		// read the server response and return it
		return readResponse(restService);
	}

	/**
	 * Retrieves the response of the rest request as json object
	 * 
	 * @param restService
	 *            The connection to the rest service
	 * @return The response as JSON object or null if no result could be retrieved
	 * @throws ServiceUnavailableException
	 */
	private JSONObject getResponseAsJson(HttpURLConnection restService) throws ServiceUnavailableException {

		// try to execute the query
		JSONObject response = getResponseAsJson(restService, getServerSessionCookie());

		// if session at the server was lost (e.g. due to service restart)
		if (response.getInt("responseCode") == 400) {

			// log in again
			response = login(getServer(), getPort(), getUsername(), getPassword());
			// if re-login was successful
			if (response.getBoolean("successful")) {
				// close the connection to the rest service
				restService.disconnect();
				restService = connectToRestService(restService.getURL().getPath());
				// try to re-execute the query
				response = getResponseAsJson(restService, getServerSessionCookie());
				// all caches need to be refilled as current state of the server is unknown
				forceRefresh();
			} else {
				logger.error("Re-login to \"" + restService.getURL().getHost() + ":" + restService.getURL().getPort() + "\"was not successful");
			}
		}

		return response.getBoolean("successful") ? response.getJSONObject("responseMessage") : null;
	}

	/**
	 * Retrieves the response of the rest request as json object
	 * 
	 * @param restService
	 *            The connection to the rest service
	 * @return A JSON object containing the following attributes:
	 *         <ul>
	 *         <li><b>responseCode</b> - The HTTP response code</li>
	 *         <li><b>successful</b> - true if the action was successful, false otherwise</li>
	 *         <li><b>responseMessage</b> - The response from the server in JSON format, if the communication was successful. Otherwise the error
	 *         message</li>
	 *         </ul>
	 * @throws ServiceUnavailableException
	 *             If the Mirth instance is not available
	 */
	private static JSONObject getResponseAsJson(HttpURLConnection restService, String sessionCookie) throws ServiceUnavailableException {
		// fetch the data
		JSONObject response = getResponseAsXml(restService, sessionCookie);

		if (response.getBoolean("successful")) {
			// change the return value to JSON
			JSONObject payload = XML.toJSONObject(response.getString("responseMessage"));

			if (payload.has("list")) {
				// check if any data was obtained
				if (!payload.get("list").equals("")) {
					// indeed. Remove the wrapping
					payload = payload.getJSONObject("list");
				} else {
					// nope, indicate it by altering the success flag
					response.put("successful", false);
					// set the return code to "no content found"
					response.put("responseCode", 204);
					// and the response message to empty
					payload = new JSONObject();
				}
			}

			// set the response message as JSON
			response.put("responseMessage", payload);
		}
		return response;
	}

	/**
	 * Retrieves the response of the rest request in plain text format<br/>
	 * <br/>
	 * Mirth provides some information, like the Mirth version, as plain text
	 * 
	 * @param restService
	 *            The connection to the rest service
	 * @return The response from the server as plain text, if the communication was successful. Otherwise an empty string
	 *         </ul>
	 * @throws ServiceUnavailableException
	 */
	private String getResponseAsPlainText(HttpURLConnection restService) throws ServiceUnavailableException {

		// try to execute the query
		JSONObject response = getResponseAsPlainText(restService, getServerSessionCookie());

		if (response.getInt("responseCode") == 400) {
			// log in again
			response = login(getServer(), getPort(), getUsername(), getPassword());
			// if re-login was successful
			if (response.getBoolean("successful")) {
				// try to re-execute the query
				response = getResponseAsPlainText(restService, getServerSessionCookie());
			}
		}
		return response.getBoolean("successful") ? response.getString("responseMessage") : "";
	}

	/**
	 * Retrieves the response of the rest request in plain text format<br/>
	 * <br/>
	 * Mirth provides some information, like the Mirth version, as plain text
	 * 
	 * @param restService
	 *            The connection to the rest service
	 * @param sessionCookie
	 *            The cookie that identifies an active session
	 * @return A JSON object containing the following attributes:
	 *         <ul>
	 *         <li><b>responseCode</b> - The HTTP response code</li>
	 *         <li><b>successful</b> - true if the action was successful, false otherwise</li>
	 *         <li><b>responseMessage</b> - The response from the server as plain text, if the communication was successful. Otherwise the error
	 *         message</li>
	 *         </ul>
	 * @throws IOException
	 * @throws ServiceUnavailableException
	 *             If the Mirth instance is not available
	 */
	private static JSONObject getResponseAsPlainText(HttpURLConnection restService, String sessionCookie) throws ServiceUnavailableException {

		try {
			// no worries, this method is correct
			restService.setRequestMethod("GET");
		} catch (ProtocolException e) {
		}
		restService.setRequestProperty("Accept", "text/plain");
		restService.setRequestProperty("X-Requested-With", MirthMigrator.clientIdentifier);
		restService.setRequestProperty("Cookie", sessionCookie);

		// read the server response and return it
		return readResponse(restService);
	}

	public String getSystemName() {
		return systemName;
	}

	private void setSystemName(String systemName) {
		this.systemName = systemName;
	}

	public String getEnvironment() {
		return environment;
	}

	private void setEnvironment(String environment) {
		this.environment = environment;
	}

	/**
	 * Gets the mirth server to which this client instance connects
	 * 
	 * @return The mirth server
	 */
	public String getServer() {
		return server;
	}

	private void setServer(String server) {
		this.server = server;
	}

	public String getUsername() {
		return username;
	}

	private void setUsername(String username) {
		this.username = username;
	}

	private String getPassword() {
		return password;
	}

	private void setPassword(String password) {
		this.password = password;
	}

	/**
	 * Gets the port of the mirth server to which this client connects
	 * 
	 * @return The port of the mirth server
	 */
	public int getPort() {
		return port;
	}

	private void setPort(int port) {
		this.port = port;
	}

	public String getDescription() {
		return description;
	}

	private void setDescription(String description) {
		this.description = description;
	}

	public String getHash() {
		return hash;
	}

	private void setHash(String hash) {
		this.hash = hash;
	}

	private String getServerSessionCookie() throws ServiceUnavailableException {
		if(serverSessionCookie == null) {
			createServerSession();
		}
		return serverSessionCookie;
	}

	/**
	 * Creates a new user session and adds it to the cache.<br/>
	 * <br/>
	 * A new user session contains the following attributes:
	 * <ul>
	 * <li><b>username</b> - the username of the user who established the session</li>
	 * <li><b>sessionCookie</b> - The session identifier (session cookie)</li>
	 * <li><b>established</b> - The point of time at which the session was created</li>
	 * <li><b>lastAccess</b> - The point of time at which the session was last accessed</li>
	 * </ul>
	 * 
	 * @param username
	 *            the username for which the session was created
	 * @param userSessionCookie
	 *            The session id. This is also the identifier of the session.
	 */
	private static void setUserSession(String username, String userSessionCookie) {
		setUserSession(username, userSessionCookie, false);
	}

	/**
	 * Creates a new user session and adds it to the cache.<br/>
	 * <br/>
	 * A new user session contains the following attributes:
	 * <ul>
	 * <li><b>username</b> - the username of the user who established the session</li>
	 * <li><b>sessionCookie</b> - The session identifier (session cookie)</li>
	 * <li><b>established</b> - The point of time at which the session was created</li>
	 * <li><b>lastAccess</b> - The point of time at which the session was last accessed</li>
	 * </ul>
	 * 
	 * @param username
	 *            the username for which the session was created
	 * @param userSessionCookie
	 *            The session id. This is also the identifier of the session.
	 */
	private static void setUserSession(String username, String userSessionCookie, boolean removeFormerSessions) {

		// first remove any sessions from the cache that exceeded their life-span and also any pre-existing sessions of the user, if required
		cleanupUserSessions(removeFormerSessions ? username : null);

		// create a new session
		HashMap<String, Object> userSession = new HashMap<String, Object>();
		// add the username
		userSession.put("username", username);
		// the session id
		userSession.put("sessionCookie", userSessionCookie);
		// and it's birth date
		userSession.put("established", System.currentTimeMillis());
		// and also indicate the point of time at which it was last accessed
		userSession.put("lastAccess", System.currentTimeMillis());
		// finally add it to the cache
		userSessionCache.put(userSessionCookie, userSession);
	}

	/**
	 * Provides a user session
	 * 
	 * @param userSessionCookie
	 *            The cookie that identifies the user session
	 * @return The user session or null if no active session could be found
	 */
	public static HashMap<String, Object> getUserSession(String userSessionCookie) {
		// if the session is valid, return it
		return isValidUserSession(userSessionCookie) ? MirthMigrator.userSessionCache.get(userSessionCookie) : null;
	}

	/**
	 * Indicates if there still is an active user session.
	 * 
	 * If the session is still active, it's lifetime is reset.
	 * 
	 * @param userSessionCookie
	 *            The cookie that identifies the user session
	 * @return true, if there still is an active user session, false otherwise
	 */
	public static boolean isValidUserSession(String userSessionCookie) {
		// get the user session
		HashMap<String, Object> userSession = MirthMigrator.userSessionCache.get(userSessionCookie);

		// if there is a user session
		if (userSession != null) {
			// but it is no longer valid
			if (System.currentTimeMillis() - ((long) userSession.get("lastAccess")) > getUserSessionLifeSpan() * 60000) {
				synchronized (MirthMigrator.userSessionCache) {
					// remove it from cache
					MirthMigrator.userSessionCache.remove(userSessionCookie);
				}
				// and invalidate the fetched session
				userSession = null;
			} else {
				// reset the session life
				userSession.put("lastAccess", System.currentTimeMillis());
			}
		}

		// check if there still is a session for the given cookie in the cache
		return userSession != null;
	}

	/**
	 * Checks the inactivity period of all cached user sessions. If the inactivity period is too long, the session is removed from the cache and thus
	 * ended.
	 */
	private static void cleanupUserSessions() {
		cleanupUserSessions(null);
	}

	/**
	 * Checks the inactivity period of all cached user sessions. If the inactivity period is too long, the session is removed from the cache and thus
	 * terminated. In addition, if an account name is specified, all sessions for that account are also removed, regardless of their validity. This is
	 * useful to avoid redundant user sessions in case of re-login.
	 * 
	 * @param account
	 *            The account for which sessions should be terminated, regardless of their validity.
	 */
	private static void cleanupUserSessions(String account) {

		synchronized (MirthMigrator.userSessionCache) {
			// check all sessions in session cache
			Iterator<Map.Entry<String, HashMap<String, Object>>> iterator = MirthMigrator.userSessionCache.entrySet().iterator();
			while (iterator.hasNext()) {
				// treat next session
				Map.Entry<String, HashMap<String, Object>> entry = iterator.next();
				HashMap<String, Object> session = entry.getValue();
				// if the life-span of the session has expired
				if ((System.currentTimeMillis() - ((long) session.get("lastAccess")) > getUserSessionLifeSpan() * 60000)
						|| ((account != null) && (session.get("username").equals(account)))) {
					// remove it from cache
					iterator.remove();
				}
			}
		}
	}

	private void setServerSessionCookie(String serverSessionCookie) {
		this.serverSessionCookie = serverSessionCookie;
	}

	private static void setUserSessionLifeSpan(Integer lifespanInMinutes) {
		MirthMigrator.userSessionLifeSpanInMinutes = lifespanInMinutes;
	}

	public static Integer getUserSessionLifeSpan() {
		return MirthMigrator.userSessionLifeSpanInMinutes;
	}

	private static void setConfigurationLoadingDate(long configurationLoadingDate) {
		MirthMigrator.configurationLoadingDate = configurationLoadingDate;
	}

	public static Long getConfigurationLoadingDate() {
		return MirthMigrator.configurationLoadingDate;
	}

	/**
	 * Fetches the mirth version from the server
	 * 
	 * @return The structured mirth version
	 * @throws ServiceUnavailableException
	 */
	public MirthVersion getMirthVersion() throws ServiceUnavailableException {
		if (this.mirthVersion == null) {
			HttpURLConnection urlConnection = connectToRestService("/api/server/version");
			String response = getResponseAsPlainText(urlConnection);
			response = response.replaceAll("\n", "");

			this.mirthVersion = new MirthVersion(response);
		}

		return this.mirthVersion;
	}

	/**
	 * Provides a code template configuration from the server
	 * 
	 * @param codeTemplateId
	 *            an ID identifying a code template
	 * @return the code template library configuration
	 * @throws ServiceUnavailableException
	 */
	private String getCodeTemplate(String codeTemplateId) throws ServiceUnavailableException {
		HttpURLConnection urlConnection = connectToRestService("/api/codeTemplates?codeTemplateId=" + codeTemplateId);

		return getResponseAsXml(urlConnection);
	}

	/**
	 * Provides channel configuration from the server
	 * 
	 * @param channelId
	 *            an ID identifying a channel
	 * @return the channel group configuration
	 * @throws ServiceUnavailableException
	 */
	public String getChannel(String channelId) throws ServiceUnavailableException {
		HttpURLConnection urlConnection = connectToRestService("/api/channels?channelId=" + channelId);

		return getResponseAsXml(urlConnection);
	}

	/**
	 * Provides the code template library configuration of the server
	 * 
	 * @return the code template library configuration
	 * @throws ServiceUnavailableException
	 */
	private String getCodeTemplateLibraries() throws ServiceUnavailableException {
		HttpURLConnection urlConnection = connectToRestService("/api/codeTemplateLibraries?includeCodeTemplates=false");

		return getResponseAsXml(urlConnection);
	}

	/**
	 * Provides the channel group configuration of the server
	 * 
	 * @return the channel group configuration
	 * @throws ServiceUnavailableException
	 */
	private String getChannelGroups() throws ServiceUnavailableException {
		HttpURLConnection urlConnection = connectToRestService("/api/channelgroups");

		return getResponseAsXml(urlConnection);
	}

	/**
	 * Provides a list of all channel tags on the server
	 * 
	 * @return a list of all channel tags
	 * @throws ServiceUnavailableException
	 */
	private String getChannelTags() throws ServiceUnavailableException {
		HttpURLConnection urlConnection = connectToRestService("/api/server/channelTags");

		return getResponseAsXml(urlConnection);
	}

	/**
	 * Provides a list of all inter-channel dependencies on the server
	 * 
	 * @return a list of all inter-channel dependencies
	 * @throws ServiceUnavailableException
	 */
	private String getInterChannelDependencies() throws ServiceUnavailableException {
		HttpURLConnection urlConnection = connectToRestService("/api/server/channelDependencies");

		return getResponseAsXml(urlConnection);
	}

	/**
	 * Provides the channel pruning configuration from the server
	 * 
	 * @return a list of all channel pruning settings
	 * @throws ServiceUnavailableException
	 */
	private String getChannelPruning() throws ServiceUnavailableException {
		HttpURLConnection urlConnection = connectToRestService("/api/server/channelMetadata");

		return getResponseAsXml(urlConnection);
	}

	/**
	 * Migrates a list of channels and all their dependencies to a target mirth instance
	 * 
	 * @param targetSystem
	 *            A Mirth client for the target system
	 * @param channelIds
	 *            A list of IDs for channels that should be migrated
	 * @return A JSON Object containing the following information:
	 *         <ul>
	 *         <li><b>success</b> - a JSON Array of successfully migrated components. Each entry contains the following attributes:
	 *         <ul>
	 *         <li><b>componentId</b> - the id of the component</li>
	 *         <li><b>componentType</b> - the type of the component. It might be:
	 *         <ul>
	 *         <li><b>channel</b> - the channels themselves</li>
	 *         <li><b>channelGroup</b> - the containers of the migrated channels</li>
	 *         <li><b>codeTemplateLibrary</b> - needed for the code template library dependencies</li>
	 *         <li><b>channelTag</b> - the tags assigned to the migrated channels</li>
	 *         </ul>
	 *         </li>
	 *         </ul>
	 *         </li>
	 *         <li><b>failure</b> - a JSON Array of components that failed the migration. Each entry contains the following attributes:
	 *         <ul>
	 *         <li><b>componentId</b> - the id of the code template</li>
	 *         <li><b>componentType</b> - the type of the component. It might be:
	 *         <ul>
	 *         <li><b>channel</b> - the channels themselves</li>
	 *         <li><b>channelGroup</b> - the containers of the migrated channels</li>
	 *         <li><b>codeTemplateLibrary</b> - needed for the code template library dependencies</li>
	 *         <li><b>channelTag</b> - the tags assigned to the migrated channels</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>headers</b> - all headers of the update request <i>(not for IO Exception)</i></li>
	 *         <li><b>configuration</b> - the altered code template configuration in the format of the target system</li>
	 *         <li><b>errorCode</b> - the HTTP error code <i>(not for IO Exception)</i></li>
	 *         <li><b>errorMessage</b> - a more detailed error message</li>
	 *         </ul>
	 *         </li>
	 *         </ul>
	 *         Mainly in case of invalid configurations. Check Exception message for details
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 */
	private JSONObject migrateChannels(MirthMigrator targetSystem, String[] channelIds)
			throws ConfigurationException, ServiceUnavailableException {
		JSONObject result, overallResult, element;
		JSONArray newComponents, worklist;
		boolean operationSucceeded = false;

		overallResult = new JSONObject();
		JSONArray success = new JSONArray();
		JSONArray failure = new JSONArray();
		
		/** Check for all channels if an id replacement is needed */
		
		for (int index = 0; index < channelIds.length; index++) {
			
			String channelId = channelIds[index];
			// get the name of the current channel
			String sourceChannelName = getChannelNameById(channelId);
			// check if there is a channel w/ the same name at the target system
			String targetChannelId = targetSystem.getChannelIdByName(sourceChannelName);
			
			// if a channel w/ the same name was found in the target system
			if(targetChannelId != null) {
				// if the channel at the target system possesses the same name but a different id (Mirth uses the id,  not the name)
				if(channelId.compareTo(targetChannelId) != 0) {
					// adapt the id of the channel that has to be migrated to the one of the target system
					channelIds[index] = channelId + ":" + targetChannelId;
				}
			} else {
				// There is not yet a channel w/ the same name in the target system but there might be an ID collision
				if(targetSystem.getChannelNameById(channelId) != null) {
					// indeed, the id is already occupied by another channel - issue a new one
					String newChannelId = UUID.randomUUID().toString();
					// adapt the id of the channel that has to be migrated to a new one that does not yet exist in the target system
					channelIds[index] = channelId + ":" + newChannelId;	
				}
			}
		}
		
		// migrate the channels
		result = updateChannels(targetSystem, channelIds);
		// add the successfully migrated channels to the list
		success.putAll(result.get("success"));
		failure.putAll(result.get("failure"));

		overallResult.put("success", success);
		overallResult.put("failure", failure);

		// add dependencies and attributes only for channels that were successfully migrated
		ArrayList<String> migratedWithSuccess = new ArrayList<String>();
		// thus firstly, assemble a list of ids of channels that were migrated successfully
		for (int index = 0; index < success.length(); index++) {
			// add channel id to list for further migration
			migratedWithSuccess.add(success.getJSONObject(index).getString("id"));
		}

		// transform it back to a string array
		channelIds = migratedWithSuccess.toArray(new String[migratedWithSuccess.size()]);

		// adjust the channel group configuration of the target system and migrate it
		result = updateChannelGroups(targetSystem, channelIds);

		// check if operation was successful
		operationSucceeded = result.getBoolean("success");
		// determine the list to which the migrated elements should be added
		worklist = operationSucceeded ? success : failure;
		newComponents = result.getJSONArray("newComponents");

		// add the information to the respective list
		for (int index = 0; index < newComponents.length(); index++) {
			// add channel group info to either the success or failure report
			element = newComponents.getJSONObject(index);
			// in case of failure, further details will be added
			if (!operationSucceeded) {
				element.put("headers", result.getString("headers"));
				element.put("configuration", result.getString("configuration"));
				element.put("errorCode", result.getInt("errorCode"));
				element.put("errorMessage", result.getString("errorMessage"));
			}
			worklist.put(element);
		}
		
		// if no channel could be migrated successfully
		if(migratedWithSuccess.size() == 0) {
			return overallResult;
		}
		// adjust the code template library dependencies of the target system and migrate them
		result = updateCodeTemplateLibraries(targetSystem, null, channelIds);
		// check if operation was successful
		operationSucceeded = result.getBoolean("success");
		// determine the list to which the migrated elements should be added
		worklist = operationSucceeded ? success : failure;
		newComponents = result.getJSONArray("newComponents");
		// add the information to the respective list
		for (int index = 0; index < newComponents.length(); index++) {
			// add info about the effort to update code template libary references to either the success or failure report
			element = new JSONObject();
			element.put("name", "Code Template Library References");
			element.put("type", "codeTemplateLibraryReferences");
			// in case of failure, further details will be added
			if (!operationSucceeded) {
				element.put("headers", result.getString("headers"));
				element.put("configuration", result.getString("configuration"));
				element.put("errorCode", result.getInt("errorCode"));
				element.put("errorMessage", result.getString("errorMessage"));
			}
			worklist.put(element);
		}

		// adjust the channel tags of the target system and migrate them
		result = updateChannelTags(targetSystem, channelIds);
		// check if operation was successful
		operationSucceeded = result.getBoolean("success");
		// determine the list to which the migrated elements should be added
		worklist = operationSucceeded ? success : failure;
		newComponents = result.getJSONArray("newComponents");
		// add the information to the respective list
		for (int index = 0; index < newComponents.length(); index++) {
			// add channel id to list for further migration
			element = new JSONObject();
			// add the component id
			element.put("name", newComponents.getString(index));
			element.put("type", result.getString("type"));
			if (!operationSucceeded) {
				element.put("headers", result.getString("headers"));
				element.put("configuration", result.getString("configuration"));
				element.put("errorCode", result.getInt("errorCode"));
				element.put("errorMessage", result.getString("errorMessage"));
			}
			worklist.put(element);
		}
		// adjust the channel pruning settings of the target system and migrate them
		result = updateChannelPrunings(targetSystem, channelIds);
		// check if operation was successful
		operationSucceeded = result.getBoolean("success");
		// determine the list to which the migrated elements should be added
		worklist = operationSucceeded ? success : failure;
		element = new JSONObject();
		// add the component id
		element.put("name", "Pruning Information");
		element.put("type", CHANNEL_PRUNING);
		if (!operationSucceeded) {
			element.put("headers", result.getString("headers"));
			element.put("configuration", result.getString("configuration"));
			element.put("errorCode", result.getInt("errorCode"));
			element.put("errorMessage", result.getString("errorMessage"));
		}
		worklist.put(element);
		
		// adjust inter-channel dependencies and migrate them
		result = updateInterChannelDependencies(targetSystem, channelIds);
		// check if operation was successful
		operationSucceeded = result.getBoolean("success");
		// determine the list to which the migrated elements should be added
		worklist = operationSucceeded ? success : failure;
		element = new JSONObject();
		// add the component id
		element.put("name", "Channel Dependencies");
		element.put("type", INTER_CHANNEL_DEPENDENCY);
		if (!operationSucceeded) {
			element.put("headers", result.getString("headers"));
			element.put("configuration", result.getString("configuration"));
			element.put("errorCode", result.getInt("errorCode"));
			element.put("errorMessage", result.getString("errorMessage"));
		}
		worklist.put(element);

		
		return overallResult;
	}

	/**
	 * Migrates a list of code templates and all their dependencies to a target mirth instance
	 * 
	 * @param targetSystem
	 *            A Mirth client for the target system
	 * @param codeTemplateIds
	 *            A list of IDs for code templates that should be migrated
	 * @return A JSON Object containing the following information:
	 *         <ul>
	 *         <li><b>success</b> - a JSON Array of successfully migrated components. Each entry contains the following attributes:
	 *         <ul>
	 *         <li><b>componentId</b> - the id of the component</li>
	 *         <li><b>componentType</b> - the type of the component. It might be:
	 *         <ul>
	 *         <li><b>code template</b> - the code template themselves</li>
	 *         <li><b>codeTemplateLibrary</b> - the code template library w/i which the code template will be displayed</li>
	 *         </ul>
	 *         </li>
	 *         </ul>
	 *         </li>
	 *         <li><b>failure</b> - a JSON Array of components that failed the migration. Each entry contains the following attributes:
	 *         <ul>
	 *         <li><b>componentId</b> - the id of the component</li>
	 *         <li><b>componentType</b> - the type of the component. It might be:
	 *         <ul>
	 *         <li><b>code template</b> - the code template themselves</li>
	 *         <li><b>codeTemplateLibrary</b> - the code template library w/i which the code template will be displayed</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>headers</b> - all headers of the update request <i>(not for IO Exception)</i></li>
	 *         <li><b>configuration</b> - the altered code template configuration in the format of the target system</li>
	 *         <li><b>errorCode</b> - the HTTP error code <i>(not for IO Exception)</i></li>
	 *         <li><b>errorMessage</b> - a more detailed error message</li>
	 *         </ul>
	 *         </li>
	 *         </ul>
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 */
	private JSONObject migrateCodeTemplates(MirthMigrator targetSystem, String[] codeTemplateIds)
			throws ConfigurationException, ServiceUnavailableException {

		JSONObject result, overallResult, element;
		JSONArray newComponents, worklist;
		boolean operationSucceeded = false;

		overallResult = new JSONObject();
		JSONArray success = new JSONArray();
		JSONArray failure = new JSONArray();
		
		/** Check for all code templates if an id replacement is needed */
		for (int index = 0; index < codeTemplateIds.length; index++) {
			
			String codeTemplateId = codeTemplateIds[index];
			// get the name of the current code template
			String sourceCodeTemplateName = getCodeTemplateNameById(codeTemplateId);
			// check if there is a code template w/ the same name at the target system
			String targetCodeTemplateId = targetSystem.getCodeTemplateIdByName(sourceCodeTemplateName);
			
			// if a code template w/ the same name was found in the target system
			if(targetCodeTemplateId != null) {
				// if the code template at the target system possesses the same name but a different id (Mirth uses the id,  not the name)
				if(codeTemplateId.compareTo(targetCodeTemplateId) != 0) {
					// adapt the id of the code template that has to be migrated to the one of the target system
					codeTemplateIds[index] = codeTemplateId + ":" + targetCodeTemplateId;
				}
			} else {
				// There is not yet a code template w/ the same name in the target system but there might be an ID collision
				if(targetSystem.getCodeTemplateNameById(codeTemplateId) != null) {
					// indeed, the id is already occupied by another code template - issue a new one
					String newCodeTemplateId = UUID.randomUUID().toString();
					// adapt the id of the code template that has to be migrated to a new one that does not yet exist in the target system
					codeTemplateIds[index] = codeTemplateId + ":" + newCodeTemplateId;	
				}
			}
		}

		// migrate the code templates
		result = updateCodeTemplates(targetSystem, codeTemplateIds);
		// add the successfully migrated code templates to the list
		success.putAll(result.get("success"));
		failure.putAll(result.get("failure"));

		overallResult.put("success", success);
		overallResult.put("failure", failure);

		// add code templates only for code templates that were successfully migrated
		ArrayList<String> migratedWithSuccess = new ArrayList<String>();
		for (int index = 0; index < success.length(); index++) {
			// add code templates id to list for further migration
			migratedWithSuccess.add(((JSONObject) success.get(index)).getString("id"));
		}
		// transform it back to a string array
		codeTemplateIds = migratedWithSuccess.toArray(new String[migratedWithSuccess.size()]);

		// adjust the code template libraries of the target system and migrate them
		result = updateCodeTemplateLibraries(targetSystem, codeTemplateIds, null);
		// check if operation was successful
		operationSucceeded = result.getBoolean("success");
		// determine the list to which the migrated elements should be added
		worklist = operationSucceeded ? success : failure;
		newComponents = result.getJSONArray("newComponents");
		// add the information to the respective list
		for (int index = 0; index < newComponents.length(); index++) {
			// add the information about the migrated code template library
			element = newComponents.getJSONObject(index);
			// in case of failure add further information
			if (!operationSucceeded) {
				element.put("headers", result.getString("headers"));
				element.put("configuration", result.getString("configuration"));
				element.put("errorCode", result.getString("errorCode"));
				element.put("errorMessage", result.getString("errorMessage"));
			}
			worklist.put(element);
		}

		return overallResult;
	}

	/**
	 * Transforms a list of channels to the format of the target mirth system and migrates them
	 * 
	 * @param targetSystem
	 *            A mirth client for the target mirth system to which the channels should be migrated
	 * @param channelIds
	 *            A list of IDs of channels from the source system that should be migrated
	 * @return A JSON Object containing the following elements:
	 *         <ul>
	 *         <li><b>success</b> - a JSON Array of successfully migrated mirth channels. Each entry contains the following attributes:
	 *         <ul>
	 *         <li><b>configuration</b> - the altered channel configuration in the format of the target system</li>
	 *         <li><b>name</b> - the name of the channel</li>
	 *         <li><b>id</b> - the id of the channel</li>
	 *         <li><b>type</b> - value: <b>channel</b></li>
	 *         </ul>
	 *         </li>
	 *         <li><b>failure</b> - a JSON Array of successfully migrated mirth channels. Each entry contains the following attributes:
	 *         <ul>
	 *         <li><b>configuration</b> - the altered channel configuration in the format of the target system</li>
	 *         <li><b>componentId</b> - the id of the channel</li>
	 *         <li><b>componentType</b> - value: <b>channel</b></li>
	 *         <li><b>headers</b> - all headers of the update request <i>(not for IO Exception)</i></li>
	 *         <li><b>errorCode</b> - the HTTP error code <i>(not for IO Exception)</i></li>
	 *         <li><b>errorMessage</b> - a more detailed error message</li>
	 *         </ul>
	 *         </li>
	 *         </ul>
	 * @throws ServiceUnavailableException
	 * @throws ConfigurationException 
	 */
	private JSONObject updateChannels(MirthMigrator targetSystem, String[] channelIds) throws ServiceUnavailableException, ConfigurationException {
		JSONObject overallResult, result;
		String sourceChannel;
		Matcher externalResourcesMatcher, idSeparatorMatcher, externalResourceEntityMatcher = null;

		overallResult = new JSONObject();
		overallResult.put("success", new JSONArray());
		overallResult.put("failure", new JSONArray());

		// for all channels that should be migrated
		for (String channelId : channelIds) {
	
			String replacementId = null;
			
			// check if there was a replacement for this id
			idSeparatorMatcher = idSeparatorPattern.matcher(channelId);
			if(idSeparatorMatcher.find()) {
				// indeed, so extract the original id of the source system
				channelId = idSeparatorMatcher.group(1);
				// and also the replacement id that should be used in the destination system
				replacementId = idSeparatorMatcher.group(2);
			}
			
			// fetch the actual code of the channel that should be migrated from the source system
			sourceChannel = getChannel(channelId);
			
			if(replacementId != null) {
				// adapt the id of the channel that has to be migrated
				sourceChannel = sourceChannel.replaceAll("<id>" + channelId + "</id>", "<id>" + replacementId + "</id>");
			}

			/** External resource reference adaption starts here */
			externalResourcesMatcher = externalResourcesPattern.matcher(sourceChannel);
			// if an external resources section with content was found
			if(externalResourcesMatcher.find()) {
				// scan all referenced entities
				externalResourceEntityMatcher = externalResourceEntityPattern.matcher(externalResourcesMatcher.group());
				while(externalResourceEntityMatcher.find()) {
					// get the external resource id
					String resourceId = externalResourceEntityMatcher.group(1);
					// and it's name
					String resourceName = externalResourceEntityMatcher.group(2);
					// now check if the destination Mirth instance references an external resource with the same name
					JSONObject resource = targetSystem.getExternalResource(resourceName);
					if(resource != null) {
						String targetResourceId = resource.getString("id");
						// indeed. So replace the reference by the reference to the corresponding resource of the target system (for all connectors)
						sourceChannel = sourceChannel.replaceAll("<string>" + resourceId + "</string>", "<string>" + targetResourceId + "</string>");
					}
				}	
			}

			// convert the format of the channel to the format of the target system
			sourceChannel = convert(sourceChannel, getMirthVersion(), targetSystem.getMirthVersion());
			// send the updated channel to the target system
			result = targetSystem.migrateComponent(sourceChannel);
			// check if migration worked like intended
			boolean success = result.getBoolean("success");
			// add the channel name as attribute
			result.put("name", getChannelNameById(channelId));

			// add also the channel id
			result.put("id", (replacementId == null) ? channelId : channelId + ":" + replacementId);
			// this attribute is no longer needed here
			result.remove("success");
			// add the feedback to one list or the other of the success report depending on the migration outcome
			overallResult.append(success ? "success" : "failure", result);
		}

		return overallResult;
	}

	/**
	 * Transforms a list of code templates to the format of the target mirth system and migrates them
	 * 
	 * @param targetSystem
	 *            A mirth client for the target mirth system to which the code templates should be migrated
	 * @param codeTemplateIds
	 *            A list of IDs of codetemplatess from the source system that should be migrated
	 * @return A JSON Object containing the following elements:
	 *         <ul>
	 *         <li><b>success</b> - a JSON Array of successfully migrated mirth code templates. Each entry contains the following attributes:
	 *         <ul>
	 *         <li><b>configuration</b> - the altered code template configuration in the format of the target system</li>
	 *         <li><b>id</b> - the id of the code template</li>
	 *         <li><b>type</b> - value: <b>codeTemplate</b></li>
	 *         </ul>
	 *         </li>
	 *         <li><b>error</b> - a JSON Array of successfully migrated mirth code templates. Each entry contains the following attributes:
	 *         <ul>
	 *         <li><b>configuration</b> - the altered code template configuration in the format of the target system</li>
	 *         <li><b>componentId</b> - the id of the code template</li>
	 *         <li><b>componentType</b> - value: <b>codeTemplate</b></li>
	 *         <li><b>headers</b> - all headers of the update request <i>(not for IO Exception)</i></li>
	 *         <li><b>errorCode</b> - the HTTP error code <i>(not for IO Exception)</i></li>
	 *         <li><b>errorMessage</b> - a more detailed error message</li>
	 *         </ul>
	 *         </li>
	 *         </ul>
	 * @throws ServiceUnavailableException
	 */
	private JSONObject updateCodeTemplates(MirthMigrator targetSystem, String[] codeTemplateIds) throws ServiceUnavailableException {
		JSONObject overallResult, result;
		Matcher idSeparatorMatcher = null;
		String codeTemplate;

		overallResult = new JSONObject();
		overallResult.put("success", new JSONArray());
		overallResult.put("failure", new JSONArray());

		// for all code template that should be migrated
		for (String codeTemplateId : codeTemplateIds) {
						
			String replacementId = null;
			
			// check if there was a replacement for this id
			idSeparatorMatcher = idSeparatorPattern.matcher(codeTemplateId);
			if(idSeparatorMatcher.find()) {
				// indeed, so extract the original id of the source system
				codeTemplateId = idSeparatorMatcher.group(1);
				// and also the replacement id that should be used in the destination system
				replacementId = idSeparatorMatcher.group(2);
			}

			// fetch the actual code of the code template that should be migrated from the source system
			codeTemplate = getCodeTemplate(codeTemplateId);
			
			if(replacementId != null) {
				// if there is a replacement ID, the id must be changed before migration
				codeTemplate = codeTemplate.replaceAll("<id>" + codeTemplateId + "</id>", "<id>" + replacementId + "</id>");
			}

			// convert the format of the code template to the format of the target system
			codeTemplate = convert(codeTemplate, getMirthVersion(), targetSystem.getMirthVersion());
			// send the updated code template to the target system
			result = targetSystem.migrateComponent(codeTemplate);
			// check if migration worked like intended
			boolean success = result.getBoolean("success");

			// add the code template name as attribute
			result.put("name", getCodeTemplateNameById(codeTemplateId));

			// add also the code template id
			result.put("id", (replacementId == null) ? codeTemplateId : codeTemplateId + ":" + replacementId);
			if (logger.isDebugEnabled()) {
				logger.debug("Checking for functions of code template " + codeTemplateId);
			}
			// if the code template contains functions
			if (this.codeTemplateIdToFunction.containsKey(codeTemplateId)) {
				JSONArray functionNames = new JSONArray();
				// add a list of function names
				for (String functionName : this.codeTemplateIdToFunction.get(codeTemplateId)) {
					// add the current function name to the list
					functionNames.put(functionName);
					if (logger.isDebugEnabled()) {
						logger.debug("Adding function " + functionName);
					}
				}
				// and add the list to the record
				result.put("function", functionNames);
			} else {
				if (logger.isDebugEnabled()) {
					logger.debug("No functions found!");
				}
			}

			// this attribute is no longer needed here
			result.remove("success");
			// add the feedback to one list or the other of the success report depending on the migration outcome
			overallResult.append(success ? "success" : "failure", result);
		}

		return overallResult;
	}

	/**
	 * Enriches channel group configuration of the destination system with references to channels that are about to be migrated and migrates it to the
	 * target system
	 * 
	 * @param targetSystem
	 *            A mirth client for the target mirth system to which the channel groups should be migrated
	 * @param channelIds
	 *            A list of channel ids for which the groups should be migrated
	 * @return A JSON object containing the following elements:
	 *         <ul>
	 *         <li><b>configuration</b> - the altered channel group configuration of the target system that contains the new elements. Each entry
	 *         consists of the following attibutes:
	 *         <ul>
	 *         <li><b>name</b> - the name of the new channel group</li>
	 *         <li><b>id</b> - the id of the new channel group</li>
	 *         <li><b>type</b> - channelGroup</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>type</b> - <b>channelGroup</b> (the type of any potentially new component that has been added to the target configuration)</li>
	 *         <li><b>newComponents</b> - An array containing the name of the new channel group that have been added to the target configuration</li>
	 *         <li><b>success</b> - true, if update was successful, false otherwise</li>
	 *         <li><b>headers</b> - all headers of the update request <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>errorCode</b> - the HTTP error code <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>errorMessage</b> - a more detailed error message <i>(only if migration was not successful)</i></li>
	 *         </ul>
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 */
	private JSONObject updateChannelGroups(MirthMigrator targetSystem, String[] channelIds)
			throws ConfigurationException, ServiceUnavailableException {

		Matcher idSeparatorMatcher, channelGroupMatcher, channelGroupNameMatcher, channelGroupIdMatcher, channelReferencesMatcher;
		HashMap<String, String> targetSystemChannelGroupMapping = new HashMap<String, String>();
		HashSet<String> collisionDetector = new HashSet<String>();
		TreeMap<String, JSONObject> newChannelGroups = new TreeMap<String, JSONObject>();
		String targetChannelGroups = targetSystem.getChannelGroups();

		JSONObject result = new JSONObject();
		result.put("type", "channelGroup");
		result.put("newComponents", new JSONArray());
		result.put("success", false);

		if ((channelIds == null) || (channelIds.length == 0)) {
			return result;
		}

		/** 1.) clean up & cache target configuration */
		// harmonize representation
		targetChannelGroups = targetChannelGroups.replaceAll("(\\s*)<channels\\/>", "$1<channels>$1<\\/channels>");

		// remove references to channels that should be migrated
		// loop over all channels that should be migrated
		for (String channelId : channelIds) {
			
			// check if there was a replacement for this id
			idSeparatorMatcher = idSeparatorPattern.matcher(channelId);
			if(idSeparatorMatcher.find()) {
				// use the replacement id that should be used in the destination system
				channelId = idSeparatorMatcher.group(2);
			}
			
			// and remove all potential references to the to be migrated channels from the target configuration 
			targetChannelGroups = targetChannelGroups.replaceAll("(\\s*)<channel version[^>]+>\\s*<id>" + channelId + "<\\/id>\\s*<revision>\\d+</revision>\\s*<\\/channel>", "");
		}

		channelGroupMatcher = channelGroupPattern.matcher(targetChannelGroups);

		// now loop over all target system channel groups
		while (channelGroupMatcher.find()) {
			// get the code of the channel group
			String targetSystemChannelGroupCode = channelGroupMatcher.group();

			// also get the channel group name
			channelGroupNameMatcher = namePattern.matcher(targetSystemChannelGroupCode);
			if (!channelGroupNameMatcher.find()) {
				throw new ConfigurationException("unable to find channel group name: \n" + targetSystemChannelGroupCode);
			}
			// add a mapping for the channel group identified by it's name
			targetSystemChannelGroupMapping.put(channelGroupNameMatcher.group(1), targetSystemChannelGroupCode);

			// get the channel group id
			channelGroupIdMatcher = idPattern.matcher(targetSystemChannelGroupCode);
			if (!channelGroupIdMatcher.find()) {
				throw new ConfigurationException("unable to find channel group id: \n" + targetSystemChannelGroupCode);
			}
			// and remember it (it will later on be needed for collision detection)
			collisionDetector.add(channelGroupIdMatcher.group(1));
		}

		/** 2.) fetch the channel group configuration from the source system */
		String sourceSystemChannelGroups = getChannelGroups();

		/** 3.) add the channel groups of the channels that should be migrated to the target configuration */
		channelGroupMatcher = channelGroupPattern.matcher(sourceSystemChannelGroups);
		// loop over all source system channel groups
		while (channelGroupMatcher.find()) {
			String sourceSystemChannelGroupCode = null;
			String sourceSystemChannelReferences = "";
			String sourceSystemChannelGroupName = null;
			String sourceSystemChannelGroupId = null;

			// get the code of the next channel group
			sourceSystemChannelGroupCode = channelGroupMatcher.group();

			// try to get hold of channel references
			channelReferencesMatcher = channelGroupChannelsPattern.matcher(sourceSystemChannelGroupCode);

			// if channel references were found
			if (channelReferencesMatcher.find()) {
				// remember them
				sourceSystemChannelReferences = channelReferencesMatcher.group();
			}

			// get the channel group name
			channelGroupNameMatcher = namePattern.matcher(sourceSystemChannelGroupCode);
			if (!channelGroupNameMatcher.find()) {
				throw new ConfigurationException("unable to find channel group name: \n" + sourceSystemChannelGroupCode);
			}
			sourceSystemChannelGroupName = channelGroupNameMatcher.group(1);

			// and also get the channel group id
			channelGroupIdMatcher = idPattern.matcher(sourceSystemChannelGroupCode);
			if (!channelGroupIdMatcher.find()) {
				throw new ConfigurationException("unable to find channel group id: \n" + sourceSystemChannelGroupCode);
			}
			sourceSystemChannelGroupId = channelGroupIdMatcher.group(1);

			// check for each channel if it is referenced by this channel group
			for (String channelId : channelIds) {
				
				// check if there was a replacement for this id
				idSeparatorMatcher = idSeparatorPattern.matcher(channelId);
				
				String replacementId = null;
				
				if(idSeparatorMatcher.find()) {
					// indeed, so extract the original id of the source system
					channelId = idSeparatorMatcher.group(1);
					// and also the replacement id that should be used in the destination system
					replacementId = idSeparatorMatcher.group(2);
				}
		
				// if the channel is referenced by this channel group in the source system the same channel group must exist in the target system and
				// reference the channel
				if (sourceSystemChannelReferences.contains(channelId)) {
					// if the channel group does not yet exist in the target system it has to be created as the channel is part of it
					if (!targetSystemChannelGroupMapping.containsKey(sourceSystemChannelGroupName)) {
						// clone the source channel group but remove the channel references
						String newChannelGroup = sourceSystemChannelGroupCode.replaceAll("(\\s*)<channel [\\s\\S]*?<\\/channel>", "");

						String newChannelGroupId = sourceSystemChannelGroupId;
						// if there is an id collision between the the source system channel group and any channel group at the destination system
						if (collisionDetector.contains(newChannelGroupId)) {
							// create a new channel group id
							newChannelGroupId = UUID.randomUUID().toString();
							// replace the current ID by a new one
							newChannelGroup = newChannelGroup.replaceFirst("<id>([^<]+)<\\/id>", "<id>" + newChannelGroupId + "</id>");
							// and also add it to the channel group id list for including it into future collision detection
							collisionDetector.add(newChannelGroupId);
						}
						// add the new channel group to the channel group list of the target system
						targetSystemChannelGroupMapping.put(sourceSystemChannelGroupName, newChannelGroup);

						// remember the new channel group
						JSONObject newChannelGroupInfo = new JSONObject();
						newChannelGroupInfo.put("name", sourceSystemChannelGroupName);
						newChannelGroupInfo.put("id", newChannelGroupId);
						newChannelGroupInfo.put("type", "channelGroup");
						newChannelGroups.put(sourceSystemChannelGroupName, newChannelGroupInfo);
					}

					// get the corresponding channel group of the target system
					String targetSystemChannelGroup = targetSystemChannelGroupMapping.get(sourceSystemChannelGroupName);

					// get the channel reference section of the target system channel group
					String targetSystemChannelReferences = "";
					channelReferencesMatcher = channelGroupChannelsPattern.matcher(targetSystemChannelGroup);
					// if channel references were found
					if (channelReferencesMatcher.find()) {
						// remember them
						targetSystemChannelReferences = channelReferencesMatcher.group();
					}
					// if the channel is already referenced there
					if (targetSystemChannelReferences.indexOf("<id>" + ((replacementId == null) ? channelId : replacementId) + "</id>") > -1) {
						// then there is nothing more to do
						continue;
					}

					// add the channel reference to the channel group (version will be adjusted further down by the convert() function)
					targetSystemChannelGroup = targetSystemChannelGroup.replaceAll("(\\s*)<channels>",
							"$1<channels>$1  <channel version=\"1.2.3\">$1    <id>" + ((replacementId == null) ? channelId : replacementId)
									+ "</id>$1    <revision>0</revision>$1  <\\/channel>");
					// and put it back into the cache
					targetSystemChannelGroupMapping.put(sourceSystemChannelGroupName, targetSystemChannelGroup);
				}
			}
		}

		/** 4.) assemble the channel group configuration for the target system */
		// create an empty configuration
		String targetSystemChannelGroups = "<list>\n";
		// and add all channel groups
		for (String channelGroup : targetSystemChannelGroupMapping.values()) {
			// add the channel group
			targetSystemChannelGroups += "  " + channelGroup + "\n";
		}
		// finish configuration
		targetSystemChannelGroups += "</list>\n";

		// only migrate if there is something to migrate
		if (targetSystemChannelGroupMapping.size() > 0) {
			// assure (or well, at least improve) the compatibility w/ the target mirth version
			targetSystemChannelGroups = convert(targetSystemChannelGroups, getMirthVersion(), targetSystem.getMirthVersion());

			// last but not least actually migrate the altered channel group configuration to the target system
			result = targetSystem.migrateComponent(targetSystemChannelGroups);
		}

		// add an ordered list of channel groups that have been added or were intended to be added to the target configuration
		result.put("newComponents", new JSONArray(newChannelGroups.values()));

		return result;
	}

	/**
	 * Enriches code template library configuration of the destination system with references to channels and/or code templates and migrates it to the
	 * target system
	 * 
	 * @param targetSystem
	 *            The client for the target system
	 * @param codeTemplateIds
	 *            A list of code templates that should be migrated to the target system
	 * @param channelIds
	 *            A list of channels that should be migrated to the target system
	 * @return A JSON object containing the following elements:
	 *         <ul>
	 *         <li><b>configuration</b> - the altered code template library configuration of the target system that contains the new elements</li>
	 *         <li><b>type</b> - <b>codeTemplateLibrary</b> (the type of any potentially new component that has been added to the target
	 *         configuration)</li>
	 *         <li><b>newComponents</b> - An array containing the name of the new code template library that have been added to the target
	 *         configuration. Each entry consists of the following attibutes:
	 *         <ul>
	 *         <li><b>name</b> - the name of the new code template library</li>
	 *         <li><b>id</b> - the id of the new code template library</li>
	 *         <li><b>type</b> - codeTemplateLibrary</li>
	 *         </ul>
	 *         </li>
	 *         <li><b>success</b> - true, if update was successful, false otherwise</li>
	 *         <li><b>headers</b> - all headers of the update request <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>errorCode</b> - the HTTP error code <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>errorMessage</b> - a more detailed error message <i>(only if migration was not successful)</i></li>
	 *         </ul>
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 */
	private JSONObject updateCodeTemplateLibraries(MirthMigrator targetSystem, String[] codeTemplateIds, String[] channelIds)
			throws ConfigurationException, ServiceUnavailableException {

		Matcher codeTemplateLibraryMatcher, codeTemplateLibraryNameMatcher, codeTemplateLibraryIdMatcher, channelReferencesMatcher,
				codeTemplateReferencesMatcher, idSeparatorMatcher;
		HashMap<String, String> targetSystemCodeTemplateLibraryMapping = new HashMap<String, String>();
		HashSet<String> collisionDetector = new HashSet<String>();
		TreeMap<String, JSONObject> newCodeTemplateLibraries = new TreeMap<String, JSONObject>();
		String targetCodeTemplateLibaries = targetSystem.getCodeTemplateLibraries();

		JSONObject result = new JSONObject();
		result.put("configuration", targetCodeTemplateLibaries);
		result.put("type", "codeTemplateLibrary");
		result.put("newComponents", new JSONArray());
		result.put("success", false);

		if (codeTemplateIds == null) {
			codeTemplateIds = new String[0];
		}

		if (channelIds == null) {
			channelIds = new String[0];
		}

		if ((channelIds.length == 0) && (codeTemplateIds.length == 0)) {
			return result;
		}

		/** 1.) clean up & cache target configuration */
		// harmonize representation
		targetCodeTemplateLibaries = targetCodeTemplateLibaries.replaceAll("(\\s*)<enabledChannelIds\\/>",
				"$1<enabledChannelIds>$1<\\/enabledChannelIds>");
		targetCodeTemplateLibaries = targetCodeTemplateLibaries.replaceAll("(\\s*)<disabledChannelIds\\/>",
				"$1<disabledChannelIds>$1<\\/disabledChannelIds>");
		targetCodeTemplateLibaries = targetCodeTemplateLibaries.replaceAll("(\\s*)<codeTemplates\\/>", "$1<codeTemplates>$1<\\/codeTemplates>");

		// remove references to channels that should be migrated
		// loop over all channels that should be migrated
		for (String channelId : channelIds) {
			// and remove all potential references to the channels from the target configuration (assigned channels will be determined by the source
			// system)
			targetCodeTemplateLibaries = targetCodeTemplateLibaries.replaceAll("(\\s*)<string>" + channelId + "<\\/string>", "");
		}
		// now remove references to the code templates that should be migrated from the target configuration
		// loop over all code templates that should be migrated
		for (String codeTemplateId : codeTemplateIds) {
			
			// check if there was a replacement for this id
			idSeparatorMatcher = idSeparatorPattern.matcher(codeTemplateId);
			if(idSeparatorMatcher.find()) {
				// use the replacement id that should be used in the destination system
				codeTemplateId = idSeparatorMatcher.group(2);
				// and remove all potential references to the code template from the target configuration
				targetCodeTemplateLibaries = targetCodeTemplateLibaries
						.replaceAll("\\s*<codeTemplate version[^>]+>\\s*<id>" + codeTemplateId + "<\\/id>\\s*<\\/codeTemplate >", "");
			}	
		}

		codeTemplateLibraryMatcher = codeTemplateLibraryPattern.matcher(targetCodeTemplateLibaries);
		// now loop over all target system code template libraries
		while (codeTemplateLibraryMatcher.find()) {
			// get the code of the code template library
			String targetSystemCodeTemplateLibraryCode = codeTemplateLibraryMatcher.group();

			// also get the code template library name
			codeTemplateLibraryNameMatcher = namePattern.matcher(targetSystemCodeTemplateLibraryCode);
			if (!codeTemplateLibraryNameMatcher.find()) {
				throw new ConfigurationException("unable to find code template library name: \n" + targetSystemCodeTemplateLibraryCode);
			}
			// add a mapping for the code template library identified by it's name
			targetSystemCodeTemplateLibraryMapping.put(codeTemplateLibraryNameMatcher.group(1), targetSystemCodeTemplateLibraryCode);
			logger.debug("Detected template library \""+codeTemplateLibraryNameMatcher.group(1)+"\"");
			// get the code template library id
			codeTemplateLibraryIdMatcher = idPattern.matcher(targetSystemCodeTemplateLibraryCode);
			if (!codeTemplateLibraryIdMatcher.find()) {
				throw new ConfigurationException("unable to find code template library id: \n" + targetSystemCodeTemplateLibraryCode);
			}
			// and remember it (it will later on be needed for collision detection)
			collisionDetector.add(codeTemplateLibraryIdMatcher.group(1));
		}

		/** 2.) fetch the code template library configuration from the source system */
		String sourceSystemCodeTemplateLibraries = getCodeTemplateLibraries();

		/** 3.) add the code template libraries of the channels or code templates that should be migrated to the target configuration */
		codeTemplateLibraryMatcher = codeTemplateLibraryPattern.matcher(sourceSystemCodeTemplateLibraries);
		// loop over all source system code template libraries
		while (codeTemplateLibraryMatcher.find()) {
			// get the code of the next code template library
			String sourceSystemCodeTemplateLibraryCode = codeTemplateLibraryMatcher.group();

			String sourceSystemChannelReferences = "";
			String sourceSystemCodeTemplateReferences = "";

			// try to get hold of channel references
			channelReferencesMatcher = channelReferencesPattern.matcher(sourceSystemCodeTemplateLibraryCode);

			// if channel references were found
			if (channelReferencesMatcher.find()) {
				// remember them
				sourceSystemChannelReferences = channelReferencesMatcher.group();
			}

			// try to get hold of code template references
			codeTemplateReferencesMatcher = codeTemplateReferencesPattern.matcher(sourceSystemCodeTemplateLibraryCode);

			// if code template references were found
			if (codeTemplateReferencesMatcher.find()) {
				// remember them
				sourceSystemCodeTemplateReferences = codeTemplateReferencesMatcher.group();
			}

			// get the code template library name
			codeTemplateLibraryNameMatcher = namePattern.matcher(sourceSystemCodeTemplateLibraryCode);
			if (!codeTemplateLibraryNameMatcher.find()) {
				throw new ConfigurationException("unable to find code template library name: \n" + sourceSystemCodeTemplateLibraryCode);
			}
			String sourceSystemCodeTemplateLibraryName = codeTemplateLibraryNameMatcher.group(1);

			// and also get the code template library id
			codeTemplateLibraryIdMatcher = idPattern.matcher(sourceSystemCodeTemplateLibraryCode);
			if (!codeTemplateLibraryIdMatcher.find()) {
				throw new ConfigurationException("unable to find code template library id: \n" + sourceSystemCodeTemplateLibraryCode);
			}
			String sourceSystemCodeTemplateLibraryId = codeTemplateLibraryIdMatcher.group(1);

			// check for each code template if it is referenced by this code template library (should be done before the channel checking because of
			// function dependencies)
			for (String codeTemplateId : codeTemplateIds) {
				
				String replacementId = null;
				
				// check if there was a replacement for this id
				idSeparatorMatcher = idSeparatorPattern.matcher(codeTemplateId);
				if(idSeparatorMatcher.find()) {
					// indeed, so extract the original id of the source system
					codeTemplateId = idSeparatorMatcher.group(1);
					// and also the replacement id that should be used in the destination system
					replacementId = idSeparatorMatcher.group(2);
				}
				
				// if the code template is referenced by this code template library in the source system
				if (sourceSystemCodeTemplateReferences.contains(codeTemplateId)) {
					logger.debug("Code Template Library \"{}\" CONTAINS code template \"{}\"", sourceSystemCodeTemplateLibraryName, getCodeTemplateNameById(codeTemplateId));

					// if the code template library does not yet exist in the destination system it has to be created as the code template is part of it
					if (!targetSystemCodeTemplateLibraryMapping.containsKey(sourceSystemCodeTemplateLibraryName)) {
						logger.debug("Adding template library \""+sourceSystemCodeTemplateLibraryName+"\" to destination configuration");			
						// clone the source system code template library but remove the code template references
						String newCodeTemplateLibrary = sourceSystemCodeTemplateLibraryCode
								.replaceAll("(\\s*)<codeTemplates[\\s\\S]*?<\\/codeTemplates>", "$1<codeTemplates>$1</codeTemplates>");
						// remove and harmonize the included channel references
						newCodeTemplateLibrary = newCodeTemplateLibrary.replaceAll("(\\s*)<enabledChannelIds[\\s\\S]*?<\\/enabledChannelIds>",
								"$1<enabledChannelIds>$1</enabledChannelIds>");
						newCodeTemplateLibrary = newCodeTemplateLibrary.replaceAll("(\\s*)<enabledChannelIds\\/>",
								"$1<enabledChannelIds>$1</enabledChannelIds>");
						// avoid having this library auto-applied to every channel by default
						newCodeTemplateLibrary = newCodeTemplateLibrary.replaceAll("(\\s*)<includeNewChannels>true<\\/includeNewChannels>",
								"$1<includeNewChannels>false</includeNewChannels>");
						// and also remove the excluded channel references
						newCodeTemplateLibrary = newCodeTemplateLibrary.replaceAll("(\\s*)<disabledChannelIds[\\s\\S]*?<\\/disabledChannelIds>",
								"$1<disabledChannelIds>$1</disabledChannelIds>");

						String newCodeTemplateLibraryId = sourceSystemCodeTemplateLibraryId;
						// if there is an id collision between the the source system code template library and any code template library at the
						// destination system
						if (collisionDetector.contains(newCodeTemplateLibraryId)) {
							// create a new code template library id
							newCodeTemplateLibraryId = UUID.randomUUID().toString();
							// replace the current ID by a new one
							newCodeTemplateLibrary = newCodeTemplateLibrary.replaceFirst("<id>([^<]+)<\\/id>",
									"<id>" + newCodeTemplateLibraryId + "</id>");
							// and also add it to the code template library id list for including it into future collision detection
							collisionDetector.add(newCodeTemplateLibraryId);
							logger.debug("Detected ID collision for library \""+sourceSystemCodeTemplateLibraryName+"\" ==> changed ID to " + newCodeTemplateLibraryId);
						}
						// add the new code template library to the code template library list of the target system
						targetSystemCodeTemplateLibraryMapping.put(sourceSystemCodeTemplateLibraryName, newCodeTemplateLibrary);

						// remember the new code template library
						JSONObject newCodeTemplateLibraryInfo = new JSONObject();
						newCodeTemplateLibraryInfo.put("name", sourceSystemCodeTemplateLibraryName);
						newCodeTemplateLibraryInfo.put("id", newCodeTemplateLibraryId);
						newCodeTemplateLibraryInfo.put("type", "codeTemplateLibrary");
						newCodeTemplateLibraries.put(sourceSystemCodeTemplateLibraryName, newCodeTemplateLibraryInfo);
					}

					// get the corresponding code template library of the target system
					String targetSystemCodeTemplateLibrary = targetSystemCodeTemplateLibraryMapping.get(sourceSystemCodeTemplateLibraryName);

					// get the code template reference section of the target system code template library
					String targetSystemCodeTemplateReferences = "";
					codeTemplateReferencesMatcher = codeTemplateReferencesPattern.matcher(targetSystemCodeTemplateLibrary);
					// if code template references were found
					if (codeTemplateReferencesMatcher.find()) {
						// remember them
						targetSystemCodeTemplateReferences = codeTemplateReferencesMatcher.group();
					}
					// if the code template is already referenced there
					if (targetSystemCodeTemplateReferences.indexOf("<id>" + ((replacementId == null) ? codeTemplateId : replacementId) + "</id>") > -1) {
						if(logger.isDebugEnabled()) {
							logger.debug("Code Template \"{}\" ({}) is already referenced in code template lbrary \"{}\"", getCodeTemplateNameById(codeTemplateId), ((replacementId == null) ? codeTemplateId : replacementId), sourceSystemCodeTemplateLibraryName);
						}
						// then there is nothing more to do
						continue;
					}
					// add the code template reference to the code template library (version will be adjusted at migration)
					targetSystemCodeTemplateLibrary = targetSystemCodeTemplateLibrary.replaceAll("(\\s*)<codeTemplates>",
							"$1<codeTemplates>$1  <codeTemplate version=\"1.2.3\">$1    <id>" + ((replacementId == null) ? codeTemplateId : replacementId) + "</id>$1  </codeTemplate>");
					if(logger.isDebugEnabled()) {
						logger.debug("ADDED Code Template \"{}\" reference with {} {}",getCodeTemplateNameById(codeTemplateId),(replacementId == null) ? "original ID" : "replacement ID", (replacementId == null) ? codeTemplateId : replacementId);
					}
					// and put it back into the cache
					targetSystemCodeTemplateLibraryMapping.put(sourceSystemCodeTemplateLibraryName, targetSystemCodeTemplateLibrary);
				}
			}

			// now check for each channel if it is referenced by this code template library
			for (String channelId : channelIds) {
				
				String replacementId = null;
				
				// check if there was a replacement for this id
				idSeparatorMatcher = idSeparatorPattern.matcher(channelId);
				if(idSeparatorMatcher.find()) {
					// indeed, so extract the original id of the source system
					channelId = idSeparatorMatcher.group(1);
					// and also the replacement id that should be used in the destination system
					replacementId = idSeparatorMatcher.group(2);
				}
				
				// if the channel is referenced by this code template library in the source system
				if (sourceSystemChannelReferences.contains(channelId)) {
					// if the code template library does not yet exist in the destination system
					if (!targetSystemCodeTemplateLibraryMapping.containsKey(sourceSystemCodeTemplateLibraryName)) {
						// skip it - it has not yet been migrated
						continue;
					}

					// get the corresponding code template library of the target system
					String targetSystemCodeTemplateLibrary = targetSystemCodeTemplateLibraryMapping.get(sourceSystemCodeTemplateLibraryName);
					// if the channel is there already referenced
					if (targetSystemCodeTemplateLibrary.indexOf("<string>" + ((replacementId == null) ? channelId : replacementId) + "</string>") > -1) {
						// then there is nothing more to do
						continue;
					}
					// add the channel reference to the code template library
					targetSystemCodeTemplateLibrary = targetSystemCodeTemplateLibrary.replaceAll("(\\s*)<enabledChannelIds>",
							"$1<enabledChannelIds>$1  <string>" + ((replacementId == null) ? channelId : replacementId) + "</string>");
					// and put it back into the cache
					targetSystemCodeTemplateLibraryMapping.put(sourceSystemCodeTemplateLibraryName, targetSystemCodeTemplateLibrary);
				}
			}
		}

		/** 4.) assemble the code template library configuration for the destination system */
		// create an empty configuration
		String targetSystemCodeTemplateLibraries = "<list>\n";

		// and add all code template libraries
		for (String codeTemplateLibrary : targetSystemCodeTemplateLibraryMapping.values()) {
			// add the code template library
			targetSystemCodeTemplateLibraries += "  " + codeTemplateLibrary + "\n";
		}
		// finish configuration
		targetSystemCodeTemplateLibraries += "</list>\n";

		// only migrate if there is something to migrate
		if (targetSystemCodeTemplateLibraryMapping.size() > 0) {

			// assure (or well, at least improve) the compatibility w/ the target mirth version
			targetSystemCodeTemplateLibraries = convert(targetSystemCodeTemplateLibraries, getMirthVersion(), targetSystem.getMirthVersion());
			targetSystemCodeTemplateLibraries=targetSystemCodeTemplateLibraries.replaceAll("\\<enabledChannelIds\\>\\s*\\<\\/enabledChannelIds\\>", "<enabledChannelIds/>").replaceAll("\\<disabledChannelIds\\>\\s*\\<\\/disabledChannelIds\\>", "<disabledChannelIds/>");
			// last but not least actually migrate the altered code template library configuration to the target system

			result = targetSystem.migrateComponent(targetSystemCodeTemplateLibraries);
			targetCodeTemplateLibaries = targetSystem.getCodeTemplateLibraries();
		}

		// if migration was successful add a list of all new code template libraries
		result.put("newComponents", new JSONArray(result.getBoolean("success") ? newCodeTemplateLibraries.values() : null));

		return result;
	}

	/**
	 * Enriches a tag list of a target system with the tags and channel references of a list of channels that should be migrated from the source
	 * system and
	 * 
	 * @param targetSystem
	 *            The client for the target system
	 * @param channelIds
	 *            A list of channels that should be migrated to the target system
	 * @return A JSON object containing the following elements:
	 *         <ul>
	 *         <li><b>configuration</b> - the altered tag configuration of the target system that contains the new elements</li>
	 *         <li><b>componentType</b> - <b>channelTag</b> (the type of any potentially new component that has been added to the target
	 *         configuration)</li>
	 *         <li><b>newComponents</b> - An array containing the name of the new tags that have been added to the target configuration</li>
	 *         <li><b>success</b> - true, if update was successful, false otherwise</li>
	 *         <li><b>headers</b> - all headers of the update request <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>errorCode</b> - the HTTP error code <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>errorMessage</b> - a more detailed error message <i>(only if migration was not successful)</i></li>
	 *         </ul>
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 */
	private JSONObject updateChannelTags(MirthMigrator targetSystem, String[] channelIds)
			throws ConfigurationException, ServiceUnavailableException {

		Matcher idSeparatorMatcher, tagMatcher, tagNameMatcher, tagIdMatcher, channelIdMatcher;
		HashMap<String, String> targetSystemChannelTagMapping = new HashMap<String, String>();
		HashSet<String> collisionDetector = new HashSet<String>();

		TreeSet<String> newTags = new TreeSet<String>();

		JSONObject result = new JSONObject();

		String targetTagList = targetSystem.getChannelTags();
		result.put("configuration", targetTagList);
		result.put("type", "channelTag");
		result.put("newComponents", new JSONArray());
		result.put("success", false);

		if (channelIds == null) {
			channelIds = new String[0];
		}

		if (channelIds.length == 0) {
			return result;
		}

		/** 1.) fetch all tags from the target system */
		// loop over all channels that should be migrated
		for (String channelId : channelIds) {
			// check if there was a replacement for this id
			idSeparatorMatcher = idSeparatorPattern.matcher(channelId);
			// if the original channel id should be replaced in the target system (due to id collision)
			if(idSeparatorMatcher.find()) {
				// use the replacement id that should be used in the destination system
				channelId = idSeparatorMatcher.group(2);
			}
			
			// and remove all potential references to the channels from the target tag list (assigned tags will be determined by the source system)
			targetTagList = targetTagList.replaceAll("(\\s*)<string>" + channelId + "<\\/string>", "");
		}
		
		tagMatcher = channelTagPattern.matcher(targetTagList);
		// now loop over all source system tags
		while (tagMatcher.find()) {
			// get the code of the tag
			String targetSystemtagCode = tagMatcher.group();
			// standardize the channel reference section
			targetSystemtagCode = targetSystemtagCode.replaceAll("(\\s*)<channelIds\\/>", "$1<channelIds>$1</channelIds>");
			// also get the tag name
			tagNameMatcher = namePattern.matcher(targetSystemtagCode);
			if (!tagNameMatcher.find()) {
				throw new ConfigurationException("unable to find tag name: \n" + targetSystemtagCode);
			}
			// add a mapping for the tag identified by it's name
			targetSystemChannelTagMapping.put(tagNameMatcher.group(1), targetSystemtagCode);

			// get the tag id
			tagIdMatcher = idPattern.matcher(targetSystemtagCode);
			if (!tagIdMatcher.find()) {
				throw new ConfigurationException("unable to find tag id: \n" + targetSystemtagCode);
			}
			// and remember it (it will later on be needed for collision detection)
			collisionDetector.add(tagIdMatcher.group(1));
		}

		/** 2.) fetch the tag list form the source system */
		String sourceSystemTags = getChannelTags();

		/** 3.) add the tags of the channels that should be migrated to the target configuration */
		tagMatcher = channelTagPattern.matcher(sourceSystemTags);
		// loop over all source system tags
		while (tagMatcher.find()) {
			// get the code of the next tag
			String sourceSystemTagCode = tagMatcher.group();

			// try to get hold of channel references
			channelIdMatcher = channelIdPattern.matcher(sourceSystemTagCode);
			// if no channel references were found
			if (!channelIdMatcher.find()) {
				// go on w/ the next tag
				continue;
			}
			String channelReferences = channelIdMatcher.group();

			// get the tag name
			tagNameMatcher = namePattern.matcher(sourceSystemTagCode);
			if (!tagNameMatcher.find()) {
				throw new ConfigurationException("unable to find tag name: \n" + sourceSystemTagCode);
			}
			String sourceSystemTagName = tagNameMatcher.group(1);

			// and also get it's id
			tagIdMatcher = idPattern.matcher(sourceSystemTagCode);
			if (!tagIdMatcher.find()) {
				throw new ConfigurationException("unable to find tag id: \n" + sourceSystemTagCode);
			}
			String sourceSystemTagId = tagIdMatcher.group(1);

			// now check for each channel if it is referenced by this tag
			for (String channelId : channelIds) {

				String replacementId = null;
				
				// check if there was a replacement for this id
				idSeparatorMatcher = idSeparatorPattern.matcher(channelId);
				if(idSeparatorMatcher.find()) {
					// indeed, so extract the original id of the source system
					channelId = idSeparatorMatcher.group(1);
					// and also the replacement id that should be used in the destination system
					replacementId = idSeparatorMatcher.group(2);
				}
				
				// if the channel is referenced by this tag in the source system
				if (channelReferences.contains(channelId)) {
					// if the tag does not yet exist in the destination system, so it has to be created
					if (!targetSystemChannelTagMapping.containsKey(sourceSystemTagName)) {
						// clone the source system tag but remove the channel references
						String newTag = sourceSystemTagCode.replaceAll("(\\s*)<channelIds[\\s\\S]*?<\\/channelIds>", "$1<channelIds>$1</channelIds>");
						// if there is an id collision between the the source system tag and any tag at the destination system
						if (collisionDetector.contains(sourceSystemTagId)) {
							// create a new tag id
							String newTagId = UUID.randomUUID().toString();
							// replace the current ID by a new one
							newTag = sourceSystemTagCode.replaceAll("<id>([^<]+)<\\/id>", "<id>" + newTagId + "</id>");
							// and also add it to the tag id list for including it into future collision detection
							collisionDetector.add(newTagId);
						}
						// add the new tag to the tag list of the target system
						targetSystemChannelTagMapping.put(sourceSystemTagName, newTag);
						// remember the name of the new tag
						newTags.add(sourceSystemTagName);
					}

					// get the corresponding tag of the target system
					String targetSystemTag = targetSystemChannelTagMapping.get(sourceSystemTagName);
					// if the channel is there already referenced
					if (targetSystemTag.indexOf("<string>" + ((replacementId == null) ? channelId : replacementId) + "</string>") > -1) {
						// then there is nothing more to do
						continue;
					}
					// add the channel reference to the tag
					targetSystemTag = targetSystemTag.replaceAll("(\\s*)<channelIds>", "$1<channelIds>$1  <string>" + ((replacementId == null) ? channelId : replacementId) + "</string>");
					// and put it back into the cache
					targetSystemChannelTagMapping.put(sourceSystemTagName, targetSystemTag);
				}
			}
		}

		/** 4.) assemble the tag list for the destination system */
		// create an empty configuration
		String targetSystemChannelTags = "<set>\n";
		// and add all tags
		for (String tag : targetSystemChannelTagMapping.values()) {
			// add the tag
			targetSystemChannelTags += "  " + tag + "\n";
		}
		// finish configuration
		targetSystemChannelTags += "</set>\n";

		// only migrate if there is something to migrate
		if (targetSystemChannelTagMapping.size() > 0) {
			try {
				// last but not least actually migrate the altered channel tag configuration to the target system
				result = targetSystem.migrateComponent(targetSystemChannelTags);
			} catch (Exception e) {
			}
		}
		// add a list of new channel tags to the result record if migration was successful
		result.put("newComponents", new JSONArray(result.getBoolean("success") ? newTags : null));

		return result;
	}

	/**
	 * Updates the inter-channel dependencies of the channels
	 * 
	 * @param targetSystem
	 *            The client for the target system
	 * @param channelIds
	 *            A list of channels that should be migrated to the target system
	 * @return A JSON object containing the following elements:
	 *         <ul>
	 *         <li><b>configuration</b> - the altered inter-channel dependency configuration of the target system that contains the new elements</li>
	 *         <li><b>componentType</b> - <b>interChannelDependency</b> (the type of any potentially new component that has been added to the target
	 *         configuration)</li>
	 *         <li><b>success</b> - true, if update was successful, false otherwise</li>
	 *         <li><b>headers</b> - all headers of the update request <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>errorCode</b> - the HTTP error code <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>errorMessage</b> - a more detailed error message <i>(only if migration was not successful)</i></li>
	 *         </ul>
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 */
	private JSONObject updateInterChannelDependencies(MirthMigrator targetSystem, String[] channelIds) throws ServiceUnavailableException, ConfigurationException
			 {
		
		Matcher idSeparatorMatcher, missingLinkSeparaterMatcher, interChannelDependencyMatcher, interChannelDependencyDetailsMatcher;
		ArrayList<String> targetInterChannelDependencies = new ArrayList<String>();

		JSONObject result = new JSONObject();

		String interChannelDependencyList = targetSystem.getInterChannelDependencies();
		result.put("configuration", interChannelDependencyList);
		result.put("type", "interChannelDependency");
		result.put("newComponents", new JSONArray());
		result.put("success", false);

		if ((channelIds == null) || (channelIds.length == 0)) {
			return result;
		}
		
		/** 1.) remove all inter-channel dependencies that involve the channels that are about to be migrated from the target configuration */
		
		interChannelDependencyMatcher = interChannelDependencyPattern.matcher(interChannelDependencyList);
		// now loop over all inter-channel dependencies of the target system
		targetInterChannelDependency:
		while (interChannelDependencyMatcher.find()) {
			// get the code of the inter-channel dependency
			String interChannelDependencyCode = interChannelDependencyMatcher.group();

			// check for all provided channel ids if they are part of this dependency
			for (String channelId : channelIds) {
				// check if there was a replacement for this id
				idSeparatorMatcher = idSeparatorPattern.matcher(channelId);
				// if the original channel id should be replaced in the target system (due to id collision)
				if(idSeparatorMatcher.find()) {
					// use the replacement id that should be used in the destination system
					channelId = idSeparatorMatcher.group(2);
				}
				// if the concerned channel is part of this dependency
				if(interChannelDependencyCode.contains(channelId)) {
					// no need to check the remaining channel IDs for this dependency. Go on with the next dependency
					continue targetInterChannelDependency;
				}
			}

			// add it to the list of dependencies that should be part of it
			targetInterChannelDependencies.add(interChannelDependencyCode);	
		}
		
		/** 2.) add the dependencies from the source configuration as long as source and target of the dependency exist (or will exist) in the target system */
		interChannelDependencyList = getInterChannelDependencies();
		interChannelDependencyMatcher = interChannelDependencyPattern.matcher(interChannelDependencyList);
		
		// now loop over all inter-channel dependencies of the source system
		sourceInterChannelDependency:
		while (interChannelDependencyMatcher.find()) {
			// get the code of the inter-channel dependency
			String interChannelDependencyCode = interChannelDependencyMatcher.group();

			// now extract the dependent and referenced channel ID from the dependency
			interChannelDependencyDetailsMatcher = interChannelDependencyDetailsPattern.matcher(interChannelDependencyCode);
			interChannelDependencyDetailsMatcher.find();
			String sourceDependentId = interChannelDependencyDetailsMatcher.group(1);
			String sourceReferencedId = interChannelDependencyDetailsMatcher.group(2);
			String targetDependentId = null;
			String targetReferencedId = null;

			// check for all provided channel ids if one of them is part of this dependency
			for (String channelId : channelIds) {
				String replacementId = null;
				// check if there was a replacement for this id
				idSeparatorMatcher = idSeparatorPattern.matcher(channelId);
				// if the original channel id should be replaced in the target system (due to id collision)
				if(idSeparatorMatcher.find()) {
					// use the replacement id that should be used in the destination system
					channelId = idSeparatorMatcher.group(1);
					replacementId = idSeparatorMatcher.group(2);
				}
				
				// if the channel id is not part of this dependency
				if(!channelId.equals(sourceDependentId) && !channelId.equals(sourceReferencedId)){
					// go on with the next channel id
					continue;
				}
			
				//this channel id is involved in an inter-channel dependency. Check if it's counterpart is as well
				if(channelId.equals(sourceDependentId)) {
					targetDependentId = (replacementId != null) ? replacementId : channelId;
				} else {
					targetReferencedId = (replacementId != null) ? replacementId : channelId;
				}

				// one side has been found. Now try to identify the other side either in the target system or in the list of IDs that have to be migrated.
				String counterpart = (targetDependentId == null) ? sourceDependentId : sourceReferencedId;
				String sourceChannelName = getChannelNameById(counterpart);
				// if a channel name for the ID was found (should always be the case but you never know...)
				if(sourceChannelName == null) {
					// go on w/ the next dependency
					continue sourceInterChannelDependency;
				}
					
				// check if there is a channel w/ the same name on the target system
				String targetChannelId = targetSystem.getChannelIdByName(sourceChannelName);
				// if there is one
				if(targetChannelId != null) {
					// set the corresponding id
					if(targetDependentId == null) {
						// the dependent ID was missing
						targetDependentId = targetChannelId;
					} else {
						// the referenced ID was missing
						targetReferencedId = targetChannelId;							
					}
				} else {
					// Plan B: check if one of the IDs of the channels that are about to be migrated is part of the dependencies
					for (String missingLink : channelIds) {
						String missingLinkReplacement = null;
						// check if there was a replacement for this id
						missingLinkSeparaterMatcher = idSeparatorPattern.matcher(missingLink);
						// if the original channel id should be replaced in the target system (due to id collision)
						if(missingLinkSeparaterMatcher.find()) {
							// use the replacement id that should be used in the destination system
							missingLink = missingLinkSeparaterMatcher.group(1);
							missingLinkReplacement = missingLinkSeparaterMatcher.group(2);
						}
						
						// if a match between the mapping and another channel that should be migrated was found
						if(counterpart.equals(missingLink)) {
							// set the corresponding id
							if(targetDependentId == null) {
								// the dependent ID was missing
								targetDependentId = (missingLinkReplacement == null) ? missingLink : missingLinkReplacement;
							} else {
								// the referenced ID was missing
								targetReferencedId = (missingLinkReplacement == null) ? missingLink : missingLinkReplacement;							
							}
							// no need to check the remaining IDs as a match was found
							break;
						}
					}	
				}
				
				// if no mapping was found
				if((targetDependentId == null) || (targetReferencedId == null)) {
					// ignore this mapping and go on w/ the next
					continue sourceInterChannelDependency;
				}
				
				// replace the IDs
				interChannelDependencyCode.replaceFirst(sourceDependentId, targetDependentId).replaceFirst(sourceReferencedId, targetReferencedId);
				// and add the dependency to the target configuration
				targetInterChannelDependencies.add(interChannelDependencyCode);	
				// job is done for this inter-channel dependency
				break;
			}
		}
		
		/** 3.) assemble the updated  inter-channel dependency configuration for the target system */
		// create an empty configuration
		String targetSystemInterChannelDependencies = "<set>\n";
		// and add all inter-channel dependencies
		for (String interChannelDependency : targetInterChannelDependencies) {
			// add the  inter-channel dependency
			targetSystemInterChannelDependencies += "  " + interChannelDependency + "\n";
		}
		// finish configuration
		targetSystemInterChannelDependencies += "</set>\n";

		// only migrate if there is something to migrate
		if (targetInterChannelDependencies.size() > 0) {
			try {
				// convert pruning settings
				targetSystemInterChannelDependencies = convert(targetSystemInterChannelDependencies, getMirthVersion(), targetSystem.getMirthVersion());
				// last but not least actually migrate the altered channel pruning configuration to the target system
				result = targetSystem.migrateComponent(targetSystemInterChannelDependencies);
				result.put("configuration", targetSystemInterChannelDependencies);
			} catch (Exception e) {
			}
		}

		return result;
	}

	
	/**
	 * Updates the pruning options of the channels
	 * 
	 * @param targetSystem
	 *            The client for the target system
	 * @param channelIds
	 *            A list of channels that should be migrated to the target system
	 * @return A JSON object containing the following elements:
	 *         <ul>
	 *         <li><b>configuration</b> - the altered tag configuration of the target system that contains the new elements</li>
	 *         <li><b>componentType</b> - <b>channelPruning</b> (the type of any potentially new component that has been added to the target
	 *         configuration)</li>
	 *         <li><b>success</b> - true, if update was successful, false otherwise</li>
	 *         <li><b>headers</b> - all headers of the update request <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>errorCode</b> - the HTTP error code <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>errorMessage</b> - a more detailed error message <i>(only if migration was not successful)</i></li>
	 *         </ul>
	 * @throws ConfigurationException
	 * @throws ServiceUnavailableException
	 */
	private JSONObject updateChannelPrunings(MirthMigrator targetSystem, String[] channelIds)
			throws ConfigurationException, ServiceUnavailableException {
		Matcher idSeparatorMatcher, pruningMatcher, channelIdMatcher;
		HashMap<String, String> sourceSystemChannelPruningMapping = new HashMap<String, String>();
		HashMap<String, String> targetSystemChannelPruningMapping = new HashMap<String, String>();

		JSONObject result = new JSONObject();

		String sourceSystemChannelPruning = getChannelPruning();
		String targetChannelPruning = targetSystem.getChannelPruning();
		
		result.put("configuration", targetChannelPruning);
		result.put("type", "channelPruning");
		result.put("newComponents", new JSONArray());
		result.put("success", false);

		if ((channelIds == null) || (channelIds.length == 0)) {
			return result;
		}

		/** 1.) fetch the pruning configurations form the source system */
		
		pruningMatcher = pruningPattern.matcher(sourceSystemChannelPruning);
		// loop over all source system tags
		while (pruningMatcher.find()) {
			// get the code of the next tag
			String sourceSystemChannelPruningCode = pruningMatcher.group();

			// try to get hold of channel reference
			channelIdMatcher = stringPattern.matcher(sourceSystemChannelPruningCode);
			// if no channel reference was found
			if (!channelIdMatcher.find()) {
				// go on w/ the next tag
				continue;
			}
			// get the channel id of this pruning configuration
			String sourceChannelId = channelIdMatcher.group(1);
			// add a mapping for the pruning setting identified by the channel id
			sourceSystemChannelPruningMapping.put(sourceChannelId, sourceSystemChannelPruningCode);
		}

		/** 2.) fetch all pruning settings from the target system */

		pruningMatcher = pruningPattern.matcher(targetChannelPruning);
		// now loop over all target system pruning settings
		while (pruningMatcher.find()) {
			// get the code of the channel pruning
			String targetSystemChannelPruningCode = pruningMatcher.group();

			// also get the id of the channel the pruning configuration is for
			channelIdMatcher = stringPattern.matcher(targetSystemChannelPruningCode);
			if (!channelIdMatcher.find()) {
				throw new ConfigurationException("unable to find channel id: \n" + targetSystemChannelPruningCode);
			}
			// channel id
			String targetChannelId = channelIdMatcher.group(1);
			// add a mapping for the pruning setting identified by the channel id
			targetSystemChannelPruningMapping.put(targetChannelId, targetSystemChannelPruningCode);
		}

		/** 3.) add/replace the pruning configuration in the target system for the channels that should be migrated */
		
		for (String sourceChannelId : channelIds) {
			String targetChannelId = sourceChannelId;
			// check if there was a replacement for this id
			idSeparatorMatcher = idSeparatorPattern.matcher(sourceChannelId);
			// if there was one
			if(idSeparatorMatcher.find()) {
				// get the channel id of the source system
				sourceChannelId = idSeparatorMatcher.group(1);
				// and also the new channel id for the target system
				targetChannelId = idSeparatorMatcher.group(2);
			}
			
			// remove the corresponding pruning settings from the target configuration
			targetSystemChannelPruningMapping.remove(targetChannelId);
			// get the pruning setting of the channel from the source system
			String channelPruningCode = sourceSystemChannelPruningMapping.get(sourceChannelId);
			// adjust the channel id 
			channelPruningCode = channelPruningCode.replaceAll("<string>([^<]+)<\\/string>", "<string>" + targetChannelId + "</string>");
			// add set the pruning information for the new channel in the target system
			targetSystemChannelPruningMapping.put(targetChannelId, channelPruningCode);
		}


		/** 4.) assemble the updated pruning configuration for the target system */
		// create an empty configuration
		String targetSystemChannelPrunings = "<map>\n";
		// and add all channel prunings
		for (String pruning : targetSystemChannelPruningMapping.values()) {
			// add the channel pruning
			targetSystemChannelPrunings += "  " + pruning + "\n";
		}
		// finish configuration
		targetSystemChannelPrunings += "</map>\n";

		// only migrate if there is something to migrate
		if (targetSystemChannelPruningMapping.size() > 0) {
			try {
				// convert pruning settings
				targetSystemChannelPrunings = convert(targetSystemChannelPrunings, getMirthVersion(), targetSystem.getMirthVersion());
				// last but not least actually migrate the altered channel pruning configuration to the target system
				result = targetSystem.migrateComponent(targetSystemChannelPrunings);
				result.put("configuration", targetSystemChannelPrunings);
			} catch (Exception e) {
			}
		}

		return result;
	}
	

	/**
	 * Provides a map that maps code templates to the library to which they belong
	 *
	 * @return The map containing the mappings
	 * @throws ServiceUnavailableException
	 */
	private HashMap<String, String> getCodeTemplateLibraryIdByCodeTemplateId() throws ServiceUnavailableException {
		// if the mapping was not yet created
		if (this.codeTemplateLibraryIdByCodeTemplateId == null) {
			// make sure it exists before returning the reference
			getCodeTemplateLibraryInfo();
		}

		return this.codeTemplateLibraryIdByCodeTemplateId;
	}

	/**
	 * Auto-detects the type of a component
	 * 
	 * @param component
	 *            The component of which the type should be detected
	 *
	 * @return The component type:
	 *         <ul>
	 *         <li><b>{@link MirthMigrator#CHANNEL Channel}</b></li>
	 *         <li><b>{@link MirthMigrator#CODE_TEMPLATE Code template}</b></li>
	 *         <li><b>{@link MirthMigrator#CHANNEL_GROUP Channel group}</b></li>
	 *         <li><b>{@link MirthMigrator#CODE_TEMPLATE_LIBRARY Code template library}</b></li>
	 *         <li><b>{@link MirthMigrator#CHANNEL_TAG Channel tags}</b></li>
	 *         <li><b>{@link MirthMigrator#CHANNEL_PRUNING Channel prunings}</b></li>
	 *         </ul>
	 *         or <b>null</b> if the component type could not be detected.
	 */
	private static String detectComponentType(String component) {
		String componentType = null;

		if ((component == null) || (component.length() == 0)) {
			// invalid parameter
			return componentType;
		}

		if (component.contains("<codeTemplateLibrary version")) {
			// code template library must be checked before code template as it might contain code templates
			componentType = MirthMigrator.CODE_TEMPLATE_LIBRARY;
		} else if (component.contains("<channelGroup version")) {
			// channel group must be checked before channel as it might contain channels
			componentType = MirthMigrator.CHANNEL_GROUP;
		} else if (component.contains("<com.mirth.connect.model.ChannelMetadata>")) {
			componentType = MirthMigrator.CHANNEL_PRUNING;
		} else if (component.contains("<channel version=")) {
			componentType = MirthMigrator.CHANNEL;
		} else if (component.contains("<channelTag>")) {
			componentType = MirthMigrator.CHANNEL_TAG;
		} else if (component.contains("<codeTemplate version")) {
			componentType = MirthMigrator.CODE_TEMPLATE;
		} else if (component.contains("<channelDependency")) {
			componentType = MirthMigrator.INTER_CHANNEL_DEPENDENCY;
		}
		
		return componentType;
	}

	/**
	 * Converts a component between the different mirth versions. (Major changes took place between Mirth v3.4.2 and Mirth v3.5.0.) All necessary
	 * changes for compliance with the target version will be applied.<br/>
	 * <br/>
	 * <b><i>ToDo: Should be extended by the Mirth upgrading mechanism that is also used by the Mirth Administrator.<br/>
	 * That way, it can at least be assured that the migration to later versions most probably works correctly</i></b>
	 * 
	 * @param component
	 *            The XML representation of the component (channel, code template, channel group, or code template library)
	 * @param sourceVersion
	 *            The current mirth version of the component. (only version 3 and above are supported)
	 * @param targetVersion
	 *            The mirth version for which the component is needed. (only version 3 and above are supported)
	 * @return The converted component suitable for the targeted version
	 */
	public static String convert(String component, MirthVersion sourceVersion, MirthVersion targetVersion) {

		float source = sourceVersion.getVersionAsFloat();
		float target = targetVersion.getVersionAsFloat();
		// if both versions are identical
		if (source == target) {
			// assure that the correct version is set for components that have been newly added
			component = mirthVersionConversionPattern.matcher(component).replaceAll("version=\"" + targetVersion.getVersionString() + "\"");
			// nothing else has to be done
			return component;
		}

		// auto-detect the type of the component
		String componentType = detectComponentType(component);

		// channel groups were added w/ v3.4
		if ((source < 3.05f) && (target >= 3.05f)) {
			// migrate from 2016 format to 2017 format
			switch (componentType) {
			case MirthMigrator.CHANNEL:
				// nothing to do
				// component = component.replaceFirst("<enabled>[^<]+</enabled>\n", "");
				// component = component.replaceFirst("<time>[^<]+</time>\n", "");
				// component = component.replaceFirst("<timezone>[^<]+</timezone>\n", "");
				// component = component.replaceFirst("<lastModified>\n", "");
				// component = component.replaceFirst("</lastModified>\n", "");
				break;
			case MirthMigrator.CHANNEL_GROUP:
				// no enabled tag is needed for the new format
				component = component.replaceAll("<enabled>[^<]+</enabled>\n", "");
				break;
			case MirthMigrator.CODE_TEMPLATE:
				// structure slightly changes: type and code tags are now wrapped in properties tag
				component = component.replaceAll("<list>", "").replaceAll("</list>", "");
				component = component.replaceAll("<type>FUNCTION</type>\n", "");
				component = component.replaceAll("<code>",
						"<properties class=\"com.mirth.connect.model.codetemplates.BasicCodeTemplateProperties\">\n<type>FUNCTION</type>\n<code>");
				component = component.replaceAll("</code>", "</code>\n</properties>");
				break;
			case MirthMigrator.CODE_TEMPLATE_LIBRARY:
				// structure slightly changes: type and code tags are now wrapped in properties tag
				component = component.replaceAll("<type>FUNCTION</type>\n", "");
				component = component.replaceAll("<code>",
						"<properties class=\"com.mirth.connect.model.codetemplates.BasicCodeTemplateProperties\">\n<type>FUNCTION</type>\n<code>");
				component = component.replaceAll("</code>", "</code>\n</properties>");
				component = component.replaceAll("<set>", "<list>").replaceAll("</set>", "</list>");
				break;

			default:
				break;
			}

		} else if ((source >= 3.05f) && (target < 3.05f)) {
			// migrate from 2017 format to 2016 format
			switch (componentType) {
			case MirthMigrator.CHANNEL:
				//
				component = component.replaceAll("<revision>", "<enabled>true</enabled>\n\t<lastModified>\n\t\t<time>" + System.currentTimeMillis()
						+ "</time>\n\t\t<timezone>Europe/Berlin</timezone>\n\t</lastModified>\n\t<revision>");
				break;
			case MirthMigrator.CHANNEL_GROUP:
				// the old format needs an enabled tag for each channel
				component = component.replaceAll("<enabled>[^<]+</enabled>\n", "");
				component = component.replaceAll("</channel>", "<enabled>false</enabled>\n</channel>");
				break;
			case MirthMigrator.CODE_TEMPLATE:
				// the old format did not possess the properties tag
				component = component.replaceAll("<properties class=\"com.mirth.connect.model.codetemplates.BasicCodeTemplateProperties\">\n", "");
				component = component.replaceAll("</properties>\n", "");
				component = component.replaceAll("<list>", "").replaceAll("</list>", "");
				break;
			case MirthMigrator.CODE_TEMPLATE_LIBRARY:
				// the old format did not possess the properties tag
				component = component.replaceAll("<properties class=\"com.mirth.connect.model.codetemplates.BasicCodeTemplateProperties\">\n", "");
				component = component.replaceAll("</properties>\n", "");
				component = component.replaceAll("<list>", "<set>").replaceAll("</list>", "</set>");
				break;
			default:
				break;
			}
		}

		// add some special treatment for channels of which the structure changed again with version 3.7 (now transformer steps can be enabled and
		// disabled)
		if ((source < 3.07f) && (target >= 3.07f)) {
			switch (componentType) {
			case MirthMigrator.CHANNEL:
				// add enabled indicator to transformer steps
				component = component.replaceAll("</sequenceNumber>(\\s*)<script>", "</sequenceNumber>$1<enabled>true</enabled>$1<script>");
				// add enable indicator to filters
				component = component.replaceAll("</sequenceNumber>(\\s*)<operator>", "</sequenceNumber>$1<enabled>true</enabled>$1<operator>");
				// file connector can now define an idle timeout after which the connection is closed. (0 means no timeout - DEFAULT)
				component = component.replaceAll("(</destinationConnectorProperties>\\s+<scheme>FILE</scheme>.+</timeout>)(\\s*)",
						"$1$2<maxIdleTime>0</maxIdleTime>$2");
				// file connector can now close connection to the file system when not writing
				component = component.replaceAll("(</destinationConnectorProperties>\\s+<scheme>FILE</scheme>.+</timeout>)(\\s*)",
						"$1$2<keepConnectionOpen>true</keepConnectionOpen>$2");
				// javascript step has now a mirth version number (whatsoever...)
				component = component.replaceAll("<com.mirth.connect.plugins.javascriptstep.JavaScriptStep>",
						"<com.mirth.connect.plugins.javascriptstep.JavaScriptStep version=\"" + targetVersion.getVersionString() + "\">");
				component = component.replaceAll("<com.mirth.connect.plugins.javascriptrule.JavaScriptRule>",
						"<com.mirth.connect.plugins.javascriptrule.JavaScriptRule version=\"" + targetVersion.getVersionString() + "\">");

				break;
			case MirthMigrator.CHANNEL_GROUP:
			case MirthMigrator.CODE_TEMPLATE:
			case MirthMigrator.CODE_TEMPLATE_LIBRARY:
			default:
				break;
			}
		} else if ((source >= 3.07f) && (target < 3.07f)) {
			switch (componentType) {
			case MirthMigrator.CHANNEL:
				// remove enabled indicator from transformer steps
				component = component.replaceAll("</sequenceNumber>(\\s*)<enabled>[^<]*</enabled>\\s*<script>", "</sequenceNumber>$1<script>");
				// remove enabled indicators form filter rules
				component = component.replaceAll("</sequenceNumber>(\\s*)<enabled>[^<]*</enabled>\\s*<operator>", "</sequenceNumber>$1<operator>");
				// remove "keep connection open" parameter from file connector
				component = component.replaceAll("</timeout>(\\s*)<keepConnectionOpen>[^<]*</keepConnectionOpen>\\s*<maxIdleTime>",
						"</timeout>$1<maxIdleTime>");
				// remove "idle timeout" parameter from file connector
				component = component.replaceAll("</timeout>(\\s*)<maxIdleTime>[^<]*</maxIdleTime>\\s*<secure>", "</timeout>$1<secure>");
				// remove version numbers from javaScript steps and filter rules
				component = component.replaceAll("<com.mirth.connect.plugins.javascriptstep.JavaScriptStep [^>]+>",
						"<com.mirth.connect.plugins.javascriptstep.JavaScriptStep>");
				component = component.replaceAll("<com.mirth.connect.plugins.javascriptstep.JavaScriptRule [^>]+>",
						"<com.mirth.connect.plugins.javascriptstep.JavaScriptRule>");
				break;
			case MirthMigrator.CHANNEL_GROUP:
			case MirthMigrator.CODE_TEMPLATE:
			case MirthMigrator.CODE_TEMPLATE_LIBRARY:
			default:
				break;
			}
		}

		// changes introduced w/ v3.12 (error messages can now be pruned)
		if ((source < 3.12f) && (target >= 3.12f)) {
			switch (componentType) {
			case MirthMigrator.CHANNEL:
			case MirthMigrator.CHANNEL_PRUNING:

				if (!component.contains("<pruneErroredMessages>")) {
					component = component.replaceAll("</archiveEnabled>",
							"</archiveEnabled>\n          <pruneErroredMessages>false</pruneErroredMessages>");
				}

				break;

			case MirthMigrator.CHANNEL_GROUP:
			case MirthMigrator.CODE_TEMPLATE:
			case MirthMigrator.CODE_TEMPLATE_LIBRARY:
			default:
				break;
			}
		} else if ((source >= 3.12) && (target < 3.12f)) {
			switch (componentType) {
			case MirthMigrator.CHANNEL:
			case MirthMigrator.CHANNEL_PRUNING:

				// remove "pruneErroredMessages" flag from pruning settings
				component = component.replaceAll("\\s*<pruneErroredMessages>[^<]*</pruneErroredMessages>", "");
				break;

			case MirthMigrator.CHANNEL_GROUP:
			case MirthMigrator.CODE_TEMPLATE:
			case MirthMigrator.CODE_TEMPLATE_LIBRARY:
			default:
				break;
			}
		}

		// changes introduced w/ v4.1 (now id of the user who changed the channel has to be transferred)
		if ((source < 4.0f) && (target >= 4.0f)) {
			switch (componentType) {
			case MirthMigrator.CHANNEL:

				// add user id of system user to export meta data if not present
				if (!component.contains("<userId>")) {
					component = component.replaceAll("</pruningSettings>", "</pruningSettings>\n        <userId>0</userId>");
				}
				// actually I am not sure at exactly which version this has been added
				if (component.contains("</responseHeaders>") && !component.contains("<responseHeadersVariable>")) {
					component = component.replaceAll("</responseHeaders>",
							"</responseHeaders>\n      <responseHeadersVariable></responseHeadersVariable>\n      <useResponseHeadersVariable>false</useResponseHeadersVariable>");
				}
				break;
			case MirthMigrator.CHANNEL_GROUP:
			case MirthMigrator.CODE_TEMPLATE:
			case MirthMigrator.CODE_TEMPLATE_LIBRARY:
			default:
				break;
			}

		} else if ((source >= 4.0) && (target < 4.0f)) {
			switch (componentType) {
			case MirthMigrator.CHANNEL:
				// remove "useHeadersVariable" and "parametersVariable" from HttpDispatcherProperties
				component = component.replaceAll("\\s*<useHeadersVariable>[^<]*</useHeadersVariable>", "").replaceAll("\\s*<useParametersVariable>[^<]*</useParametersVariable>", "").replaceAll("\\s*<headersVariable/>", "").replaceAll("\\s*<headersVariable>[^<]*</headersVariable>", "").replaceAll("\\s*<parametersVariable/>", "").replaceAll("\\s*<parametersVariable>[^<]*</parametersVariable>", "");				
			case MirthMigrator.CHANNEL_PRUNING:
				// remove "userId" from export meta data
				component = component.replaceAll("\\s*<userId>[^<]*</userId>", "").replaceAll("\\s*<responseHeadersVariable>[^<]*</responseHeadersVariable>", "").replaceAll("\\s*<useResponseHeadersVariable>[^<]*</useResponseHeadersVariable>", "");
				break;
			case MirthMigrator.CHANNEL_GROUP:
			case MirthMigrator.CODE_TEMPLATE:
			case MirthMigrator.CODE_TEMPLATE_LIBRARY:
			default:
				break;
			}
		}

		// changes introduced w/ v4.3 (encryption of attachments & meta data)
		if ((source < 4.03f) && (target >= 4.03f)) {
			switch (componentType) {
			case MirthMigrator.CHANNEL:

				// add "encryptAttachments" and "encryptCustomMetaData" flags to general channel metadata
				if (!component.contains("<encryptAttachments>")) {
					component = component.replaceAll("</encryptData>",
							"</encryptData>\n      <encryptAttachments>false</encryptAttachments>\n      <encryptCustomMetaData>false</encryptCustomMetaData>");
				}
				
				// add "useHeadersVariable" flags to general channel metadata
				if (!component.contains("<useHeadersVariable>")) {
					component = component.replaceAll("</parameters>",
							"</parameters>\n      <useHeadersVariable>false</useHeadersVariable>\n      <headersVariable/>");
					component = component.replaceAll("<parameters class=\"linked-hash-map\"/>",
							"<parameters class=\"linked-hash-map\"/>\n      <useHeadersVariable>false</useHeadersVariable>\n      <headersVariable/>");
				}

				// add "useParametersVariable" flags to general channel metadata
				if (!component.contains("<useParametersVariable>")) {
					component = component.replaceAll("</parameters>",
							"</parameters>\n      <useParametersVariable>false</useParametersVariable>\n      <parametersVariable/>");
					component = component.replaceAll("<parameters class=\"linked-hash-map\"/>",
							"<parameters class=\"linked-hash-map\"/>\n      <useParametersVariable>false</useParametersVariable>\n      <parametersVariable/>");
				}
				
				break;
			case MirthMigrator.CHANNEL_GROUP:
			case MirthMigrator.CODE_TEMPLATE:
			case MirthMigrator.CODE_TEMPLATE_LIBRARY:
			default:
				break;
			}
		} else if ((source >= 4.03f) && (target < 4.03f)) {
			switch (componentType) {
			case MirthMigrator.CHANNEL:

				// remove "encryptAttachments" flag from general channel meta data
				component = component.replaceAll("\\s*<encryptAttachments>[^<]*</encryptAttachments>", "");
				// remove "encryptCustomMetaData" flag from general channel metadata
				component = component.replaceAll("\\s*<encryptCustomMetaData>[^<]*</encryptCustomMetaData>", "");
				break;
			case MirthMigrator.CHANNEL_GROUP:
			case MirthMigrator.CODE_TEMPLATE:
			case MirthMigrator.CODE_TEMPLATE_LIBRARY:
			default:
				break;
			}
		}

		// adapt the version number to the target version
		component = mirthVersionConversionPattern.matcher(component).replaceAll("version=\"" + targetVersion.getVersionString() + "\"");

		return component;
	}

	/**
	 * 
	 * @param urlConnection
	 *            The connection to the webservice
	 * @param groups
	 *            The groups that will be updated
	 * @param componentType
	 *            Indicates either channelGroup or codeTemplateLibrary
	 * @return a JSON object containing the following elements:
	 *         <ul>
	 *         <li><b>success</b> - true, if update was successful, false otherwise</li>
	 *         <li><b>componentType</b> - the configuration code of the group component</li>
	 *         <li><b>headers</b> - all headers of the update request <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>configuration</b> - the configuration code of the group component <i>(only if migration was not successful - not for IO
	 *         Exception)</i></li>
	 *         <li><b>errorCode</b> - the http error code <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>errorMessage</b> - a more detailed error message <i>(only if migration was not successful)</i></li>
	 *         </ul>
	 * @throws ServiceUnavailableException 
	 */
	private JSONObject pushGroupComponent(HttpURLConnection urlConnection, String groups, String componentType) throws ServiceUnavailableException {
		JSONObject result = new JSONObject();
		result.put("success", false);
		result.put("type", componentType);

		try {
			String payload = "";
			String name = (componentType.equals("channelGroup")) ? "channelGroups" : "libraries";
			String boundary = "***" + System.currentTimeMillis() + "***";
			
			if (logger.isDebugEnabled()) {
				logger.debug("Calling webservice: \n" + urlConnection.getURL().getPath());
			}
			
			// printObjectToFile(groups);
			urlConnection.setUseCaches(false);
			urlConnection.setDoOutput(true);
			urlConnection.setRequestMethod("POST");
			urlConnection.setRequestProperty("Connection", "Keep-Alive");
			urlConnection.setRequestProperty("X-Requested-With", MirthMigrator.clientIdentifier);
			urlConnection.setRequestProperty("Cookie", getServerSessionCookie());
			urlConnection.setRequestProperty("Cache-Control", "no-cache");
			urlConnection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary + "; charset=UTF-8");
			urlConnection.setRequestProperty("accept", "application/json, application/xml");
//			urlConnection.setRequestProperty("Accept", "application/json");

			payload += "--" + boundary + "\n";
			payload += "Content-Disposition: form-data; name=\"" + name + "\"\n";
			payload += "Content-Type: application/xml; charset=UTF-8\n";
			payload += "Content-Transfer-Encoding: 8bit\n\n";
			payload += groups;
			payload += "\n\n--" + boundary + "\n";

			if (componentType.equals("channelGroup")) {
				payload += "Content-Disposition: form-data; name=\"removedChannelGroupIds\"\n";
				payload += "Content-Type: application/xml; charset=UTF-8\n\n";
				payload += "<set/> \n";
				payload += "\n--" + boundary + "--\n\n";
				// send channelgroup update request to the server
				urlConnection.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
			} else {
				payload += "Content-Disposition: form-data; name=\"removedCodeTemplateIds\"\n";
				payload += "Content-Type: application/xml; charset=UTF-8\n\n";
				payload += "<set/> \n";
				payload += "\n--" + boundary + "\n\n";
				payload += "Content-Disposition: form-data; name=\"updatedCodeTemplates\"\n";
				payload += "Content-Type: application/xml; charset=UTF-8\n\n";
				payload += "<set/> \n";
				payload += "\n--" + boundary + "\n\n";
				payload += "Content-Disposition: form-data; name=\"removedLibraryIds\"\n";
				payload += "Content-Type: application/xml; charset=UTF-8\n\n";
				payload += "<set/> \n";
				payload += "\n--" + boundary + "--\n\n";
				// send code template library update request to the server
				urlConnection.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
			}
			
			if (logger.isDebugEnabled()) {
				logger.debug("Content is: \n" + payload);
			}

			urlConnection.getOutputStream().flush();
			urlConnection.getOutputStream().close();

			if ((urlConnection.getResponseCode() >= 200) && (urlConnection.getResponseCode() < 300)) {
				result.put("success", true);
			} else {
				StringBuilder builder = new StringBuilder();
				builder.append(urlConnection.getResponseCode()).append(" ").append(urlConnection.getResponseMessage()).append("\n");

				Map<String, List<String>> map = urlConnection.getHeaderFields();
				for (Map.Entry<String, List<String>> entry : map.entrySet()) {
					if (entry.getKey() == null) {
						continue;
					}
					builder.append(entry.getKey()).append(": ");

					List<String> headerValues = entry.getValue();
					Iterator<String> it = headerValues.iterator();
					if (it.hasNext()) {
						builder.append(it.next());

						while (it.hasNext()) {
							builder.append(", ").append(it.next());
						}
					}

					builder.append("\n");
				}
				result.put("headers", builder.toString());
				result.put("configuration", groups);
				result.put("errorCode", urlConnection.getResponseCode());
				result.put("errorMessage", urlConnection.getResponseMessage());
				logger.error(builder.toString());
				logger.error(groups);
				logger.error(
						"migration error (pushGroupComponent): " + urlConnection.getResponseMessage() + " (" + urlConnection.getResponseCode() + ")");
			}
		} catch (IOException e) {
			result.put("errorMessage", "Group component update failed");
			logger.error("Group component update failed: \n" + e.getMessage());
		}

		return result;
	}

	/**
	 * This function updates a specified subtype such as channel or codetemplate.
	 * 
	 * @param urlConnection
	 *            The already defined URLConnection whether for channel or codeTemplate
	 * @param component
	 *            The component content that will be updated
	 * @return a JSON object containing the following elements:
	 *         <ul>
	 *         <li><b>success</b> - true, if update was successful, false otherwise</li>
	 *         <li><b>configuration</b> - the configuration code of the component</li>
	 *         <li><b>headers</b> - all headers of the update request <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>errorCode</b> - the HTTP error code <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>errorMessage</b> - a more detailed error message <i>(only if migration was not successful)</i></li> *
	 *         </ul>
	 * @throws ServiceUnavailableException 
	 */
	private JSONObject pushLeafComponent(HttpURLConnection urlConnection, String component) throws ServiceUnavailableException {
		JSONObject result = new JSONObject();
		result.put("success", false);

		try {
			urlConnection.setRequestProperty("Content-Type", "application/xml");
			urlConnection.setRequestProperty("X-Requested-With", MirthMigrator.clientIdentifier);
			urlConnection.setRequestProperty("accept", "application/json, application/xml");
			urlConnection.setRequestMethod("PUT");
			urlConnection.setRequestProperty("Cookie", getServerSessionCookie());
			urlConnection.setDoOutput(true);
			urlConnection.setDoInput(true);
			urlConnection.getOutputStream().write(component.getBytes(StandardCharsets.UTF_8));

			if (logger.isDebugEnabled()) {
				logger.debug("Destination is: \n" + urlConnection.getURL().getPath());
				logger.debug("Content is: \n" + component);
			}

			if ((urlConnection.getResponseCode() >= 200) && (urlConnection.getResponseCode() < 300)) {
				result.put("success", true);
			} else {
				StringBuilder builder = new StringBuilder();
				builder.append(urlConnection.getResponseCode()).append(" ").append(urlConnection.getResponseMessage()).append("\n");

				Map<String, List<String>> map = urlConnection.getHeaderFields();
				for (Map.Entry<String, List<String>> entry : map.entrySet()) {
					if (entry.getKey() == null) {
						continue;
					}
					builder.append(entry.getKey()).append(": ");

					List<String> headerValues = entry.getValue();
					Iterator<String> it = headerValues.iterator();
					if (it.hasNext()) {
						builder.append(it.next());

						while (it.hasNext()) {
							builder.append(", ").append(it.next());
						}
					}

					builder.append("\n");
				}
				result.put("headers", builder.toString());
				result.put("configuration", component);
				result.put("errorCode", urlConnection.getResponseCode());
				result.put("errorMessage", urlConnection.getResponseMessage());
				logger.error(builder.toString());
				logger.error(component);
				logger.error(
						"migration error (pushLeafComponent): " + urlConnection.getResponseMessage() + " (" + urlConnection.getResponseCode() + ")");
			}
		} catch (IOException e) {
			result.put("errorMessage", "Leaf component update failed");
			logger.error("Leaf component update failed");
		}

		return result;
	}

	/**
	 * Auto-detects the id of a component
	 * 
	 * @param component
	 *            The component of which the id should be detected
	 *
	 * @return The component id. This however only makes sense for code templates or channels as container components (code template libaries or
	 *         channel groups) might contain multiple ids and only the first id is returned.
	 */
	private String detectComponentId(String component) {
		String componentId = null;

		// extract component id from the configuration
		Matcher idMatcher = MirthMigrator.idPattern.matcher(component);
		idMatcher.find();
		componentId = idMatcher.group(1);

		return componentId;
	}

	/**
	 * This function migrates a component to the selected system.
	 * 
	 * @param component
	 *            The component
	 * @return a JSON object containing the following elements:
	 *         <ul>
	 *         <li><b>success</b> - true, if update was successful, false otherwise</li>
	 *         <li><b>componentType</b> - the configuration code of the group component</li>
	 *         <li><b>headers</b> - all headers of the update request <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>configuration</b> - the configuration code of the group component<i>(only if migration was not successful - not for IO
	 *         Exception)</i></li>
	 *         <li><b>errorCode</b> - the http error code <i>(only if migration was not successful - not for IO Exception)</i></li>
	 *         <li><b>errorMessage</b> - a more detailed error message <i>(only if migration was not successful)</i></li>
	 *         </ul>
	 * @throws ServiceUnavailableException 
	 */
	private JSONObject migrateComponent(String component) throws ServiceUnavailableException {
		JSONObject result;
		boolean override = true;
		HttpURLConnection urlConnection = null;
		String componentId = null;

		// detect component type
		String componentType = detectComponentType(component);

		// and in case of channels or code templates
		if (componentType.equals(CHANNEL) || componentType.equals(CODE_TEMPLATE)) {
			// also the id is needed for the API call
			componentId = detectComponentId(component);
		}
		
		if (logger.isDebugEnabled()) {
			logger.debug("Migrating " + componentType);
		}
		
		// migrate the component
		switch (componentType) {
		case CHANNEL:
			component = component.replaceAll("^\\s*<list>", "").replaceAll("</list>\\s*$", "");
			urlConnection = connectToRestService("/api/channels/" + componentId + "?override=" + override);
			result = pushLeafComponent(urlConnection, component);
			break;
		case CHANNEL_GROUP:
			component = component.replaceAll("^\\s*<list>", "<set>").replaceAll("</list>\\s*$", "</set>");
			urlConnection = connectToRestService("/api/channelgroups/_bulkUpdate?override=" + override);
			result = pushGroupComponent(urlConnection, component, componentType);
			break;
		case CHANNEL_TAG:
			urlConnection = connectToRestService("/api/server/channelTags");
			result = pushLeafComponent(urlConnection, component);
			break;
		case INTER_CHANNEL_DEPENDENCY:
			urlConnection = connectToRestService("/api/server/channelDependencies");
			result = pushLeafComponent(urlConnection, component);
			break;
		case CHANNEL_PRUNING:
			urlConnection = connectToRestService("/api/server/channelMetadata");
			result = pushLeafComponent(urlConnection, component);
			break;
		case CODE_TEMPLATE:
			component = component.replaceAll("^\\s*<list>", "").replaceAll("</list>\\s*$", "");
			urlConnection = connectToRestService("/api/codeTemplates/" + componentId + "?override=" + override);
			result = pushLeafComponent(urlConnection, component);
			break;
		case CODE_TEMPLATE_LIBRARY:
			urlConnection = connectToRestService("/api/codeTemplateLibraries/_bulkUpdate?override=" + override);
			result = pushGroupComponent(urlConnection, component, componentType);
			break;
		default:
			// report unsupported component type
			result = new JSONObject();
			result.put("success", false);
			result.put("code", component);
			result.put("errorMessage", "Component type\"" + componentType + "\" is unknown.");
		}

		// assure that the type attribute is set for every component type
		result.put("type", componentType);

		return result;
	}
	
	public static void main(String[] args) throws Exception {
		
		//System.out.println("Writing configuration:");

	//	boolean result = setConfiguration(getConfiguration());

		// Output the stringified JSON
	//	System.out.println("Writing configuration file " + (result ? "was successful." : "has failed"));

	}
}
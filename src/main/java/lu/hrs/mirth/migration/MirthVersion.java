package lu.hrs.mirth.migration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This is basically a container for the mirth version string. It provides to compare the version elements
 * 
 * @author ortwin.donak
 *
 */
public class MirthVersion {
	/** The main version number of mirth like for v3.4.2.8029 ==> 3 */
	private int version;
	/** The revision number of mirth like for v3.4.2.8029 ==> 4 or v3.4 ==> 4 */
	private int revision;
	/** The fix number of mirth like for v3.4.2.8029 ==> 2 or v3.4 ==> 0 */
	private int fix;
	/** The build number of mirth like for v3.4.2.8029 ==> 8029 */
	private int build;

	// extract the version details from the mirth version
	private final static Pattern versionDetectionPattern = Pattern.compile("([^\\.]+)\\.(\\d+)(?:\\.(\\d*))?(?:\\.(\\d*))?");

	MirthVersion(String version) {
		// extract version data
		Matcher versionMatcher = versionDetectionPattern.matcher(version);

		if (versionMatcher.find()) {

			setVersion(Integer.parseInt(versionMatcher.group(1)));
			setRevision(Integer.parseInt(versionMatcher.group(2)));

			try {
				// revision is not always present
				setFix(Integer.parseInt(versionMatcher.group(3)));
			} catch (Exception e) {
				setFix(0);
			}
			try {
				// build number is not always present
				setBuild(Integer.parseInt(versionMatcher.group(4)));
			} catch (Exception e) {
				setBuild(0);
			}
		}

	}

	/**
	 * Provides the mirth version string like it is needed by the webservice API
	 * 
	 * @return The mirth webservice api-compatible version string
	 */
	public String getVersionString() {
		String versionString = getVersion() + "." + getRevision();
		// if there is also a fix...
		// if (getFix() > 0) {
		// ...add it to the version string
		versionString += "." + getFix();
		// }

		return versionString;
	}

	public float getVersionAsFloat() {
		int revision = getRevision();
		return Float.parseFloat(getVersion() + "." + ((revision < 10) ? "0" + revision : revision) + getFix());
	}

	public int getVersion() {
		return version;
	}

	public void setVersion(int version) {
		this.version = version;
	}

	public int getRevision() {
		return revision;
	}

	public void setRevision(int revision) {
		this.revision = revision;
	}

	public int getFix() {
		return fix;
	}

	public void setFix(int fix) {
		this.fix = fix;
	}

	public int getBuild() {
		return build;
	}

	public void setBuild(int build) {
		this.build = build;
	}
};
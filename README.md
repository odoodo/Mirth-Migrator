



<p align="center"><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/13eb2ff1-8386-41c3-8c1b-aba7127b112a" width="200"></p>
Mirth Migrator automates the migration of <a href="https://www.nextgen.com/solutions/interoperability/mirth-integration-engine/mirth-connect-downloads" target="_blank">Mirth Enterprise Service Bus</a> channels and code templates from one instance to another.
<h2>Features</h2>
<ul>
  <li><b>Code Templates and Channel Migration</b><br/>Code templates, channels, and even entire Channel Groups or Code Template Libraries can be migrated between Mirth instances with a single click. Code templates of directly or indirectly referenced functions can be automatically migrated with the channels.<br/><br/>
If a component already exists at the destination instance, conflict handling will be activated. The component to be migrated will be compared with the corresponding component of the target system, and the user can choose to skip its migration or overwrite the one on the target side.</li>
  <li><b>Meta Data and Source Code Viewing</b><br/>When a component is selected, important metadata and the source code are displayed.</li>
  <li><b>Documentation</b><br/>Upon clicking on a component, the relevant documentation is displayed. In the case of functions, this may include their descriptions, parameter definitions, and expected return values. In the case of channels, this may include a description and a change history. Multiple functions in one code template will be displayed as separate items.</li>
  <li><b>Compare</b><br/>If a component selected at one instance already exists at the other instance, it will be highlighted, and a compare button will appear. The source code and metadata for components can be compared in two ways: side-by-side or inline.</li>
  <li><b>Function Dependency Checking</b><br/>For each function, it is indicated which other functions it uses, as well as the channels and/or functions through which it is used.</li>
  <li><b>Channel Dependency Checking</b><br/>For each channel, either directly or indirectly referenced functions are shown. It is further indicated which code template libraries it references and if these references are correct. Non-needed as well as missing library references are highlighted</li>
   <li><b>Web-Based Application</b><br/>Mirth Migrator is a fully web-based application provided via a Mirth channel. There is no need for a separate user management system as it utilises the user pool of the Mirth installation, which runs the channel.</li>
<h2>Screenshots</h2>
  <kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/01fb4697-c3b1-4927-83b5-3880f2cad292" width="160"></kbd>
  <kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/582d4991-04b5-4132-805d-48aee2267c80" width="160"></kbd>
  <kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/0858502d-2135-4674-bdac-e784ab8fc1af" width="160"></kbd>
  <kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/d6752f34-d959-46cf-bbc8-e744b9284a47" width="160"></kbd>
  <kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/9ba37e59-896c-4fb0-897c-2f133880d82f" width="160"></kbd>

<h2>Installation</h2>

<table>
  <tr><td><b>1.</b></td><td>Copy the <b>folder "web"</b> to your Mirth installation (to <b>"&lt;Your Install Path&gt;\Mirth Connect"</b>)</td><td><kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/bb711974-3afe-4f3e-876b-3a1c5c83f274" width="160"></kbd></td></tr>
  <tr><td><b>2.</b></td><td>In Mirth Administrator open the <b>menu item "Settings"</b>, there the <b>tab "Resources"</b> and press <b>"Add Resources"</b></td><td><kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/eb1a83c9-0cd3-4b08-b9be-1bc331677fb2" width="160"></kbd></td></tr>
  <tr><td><b>3.</b></td><td>In the field <b>Directory</b> enter <b>"./web/MirthMigrator/jar"</b> and <b>name</b> the new Resource <b>"MirthMigrator"</b></td><td><kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/e76d7350-07e5-4983-b79d-c3c15e4ed962" width="160"></kbd></td></tr>
  <tr><td><b>4.</b></td><td><b>Save</b> the change and <b>press "Reload Resource"</b>.<br/>
    Depending on your Mirth version it should now show something like "&lt;Your Installation Path&gt;/Mirth%20Connect/web/MirthMigrator/jar/MirthMigrator.jar" or just "MirthMigrator.jar" under "Loaded Libraries"</td><td><kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/5c1229a7-67d3-47db-8815-0830d49a8a6d" width="160"></kbd></td></tr>
  <tr><td><b>5.</b></td><td>Now go to menu item "<b>Channels</b>" and press "<b>Import Channel</b>"</td><td><kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/7a8a787d-fc2f-49ec-a986-1ef7e417363d" width="160"></kbd></td></tr>
  <tr><td><b>6.</b></td><td>Import <b>MIRTH_MIGRATOR.xml</b></td><td><kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/486a7c18-c5dc-403c-8c36-26f03ee61d1e" width="160"></kbd></td></tr>
  <tr><td><b>7.</b></td><td>Within the MIRTH_MIGRATOR-channel click on <b>tab "Summary"</b> (1st tab) and press <b>"Set Dependencies"</b></td><td><kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/097d49df-9fb3-4ff3-ae88-a28b201d5185" width="160"></kbd></td></tr>
  <tr><td><b>8.</b></td><td>Choose the <b>tab "Library Resources"</b> (2nd tab) in the up-popping dialog, activate the <b>checkbox</b> for <b>"Mirth Migrator"</b> and <b>press OK</b></td><td><kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/5dc67b83-c667-475d-8a88-38e255928bfb" width="160"></kbd></td></tr>
  <tr><td><b>9.</b></td><td><b><b>Save</b> the channel changes and <b>deploy</b> the channel</td><td><kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/809cc23e-012a-4ba0-a2f4-6fc3eda93155" width="160"></kbd></td></tr>
  <tr><td><b>10.</b></td><td>Mirth Migrator should now be available under <b>http://&lt;YOUR&nbsp;MIRTH&nbsp;SERVER&gt;:1339/MirthMigrator</b><br/><br/>
  <i>$${\color{red}Please \space be \space aware \space that \space the \space Mirth \space Migrator \space needs \space to \space be \space configured \space before \space you \space can \space use \space it.}$$ $${\color{red}Check \space the \space configuration \space section \space below \space for \space details}$$</i></td><td><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/c52cb3f6-b78f-4cca-887f-00407c005f67" width="160"></td></tr>
</table>

<h2>Configuration</h2>
Mirth Migrator does not (yet) feature a graphical configuration frontend.
Thus, the configuration has to be changed in the configuration file directly.<br/>
<br/>
This <b>configuration file</b> can be found at: "&lt;Your&nbsp;Installation&nbsp;Path&gt;/Mirth&nbsp;Connect/web/MirthMigrator/config/<b>MirthMigrator.conf</b>"<br/>
<br/>
The configuration consists of 4 sections:
<table>
  <tr><td colspan=2><b>sessionLifeSpanInMinutes</b></td><td>Allows to determine the maximum inactivity period for a user session in minutes.<br/><i>If the value is 0, the user session will not expire</i></td></tr>
  <tr><td rowspan=4><b>environment</b></td><td><b>id</b></td><td>A unique identifier of the environment<br/><i>1, 2, 3, etc.)</i></td></tr>
  <tr><td><b>position</b></td><td>The order position of the environment <br/><i>1 will be displayed on top of the list, 2 will be displayed as second, etc.</i></td></tr>
  <tr><td><b>name</b></td><td>The name of the environment <br/><i>e.g. Production, Test, Development, Fallback</i></td></tr>
  <tr><td><b>color</b></td><td>The color in which servers of this environment will be shown <br/><i>use html colors</i></td></tr>
  <tr><td rowspan=7><b>system</b></td><td><b>name</b></td><td>The name of the Mirth system<br/><i>Any name can be used, e.g. Production Server 1, My Test System</i></td></tr>
  <tr><td><b>server</b></td><td>The name or ip of the Mirth server that should be accessed</td></tr>
  <tr><td><b>port</b></td><td>The port at which the Mirth server exposes it's API <br/><i>By default, this port is <b>8443</b></i></td></tr>
  <tr><td><b>environment</b></td><td>The <b>ID of the environment</b> to which this Mirth server should be assigned<br/><i>This ID must correspond to an id attribute of the environment section</i></td></tr>
  <tr><td><b>description</b></td><td>An optional text that describes your Mirth environment<br/><i>e.g. "Hosts all medical channels" or "Server for administrative channels"</i></td></tr>
  <tr><td><b>user</b></td><td>The Mirth user that Mirth Migrator should use to access this Mirth instance <br/><i>It makes sense to create a "system"-user on every Mirth instance that should be accessed by Mirth administrator. This might be a user like "system", or "maintanance", or "migration"</i></td></tr>
  <tr><td><b>password</b></td><td>The password of the Mirth user that Mirth Migrator should use to access this Mirth instance</td></tr>
  <tr><td colspan=2><b>excludeFromFunctionDetection</b></td><td>Mirth Migrator has a parser that detects function references for detecting user defined functions. <br/><br/>However there might be false positives like function calls in SQL statements or calls to Mirth maps. Those functions would be displayed as functions for which the source could not be identified. <br/><br/>For avoiding false positives, the function names can be added to this filter list.</td></tr>
</table>

Any <b>changes</b> of the configuration <b>are applied on the fly</b>. There is no need to restart the MIRTH_Migrator channel.

When you access Mirth Migrator, it asks you to <b>log in</b>. Simply <b>use your Mirth account</b> of the Mirth instance at which the MIRTH_MIGRATOR channel is running.

<h2>Kudos</h2>
Mirth Migrator makes use of a number of other open source projects:
<ul>
<li><a href="https://github.com/stleary/JSON-java" target="_blank">JSON in Java</a> (A JSON implementation for Java)</li>
<li><a href="https://github.com/qiao/difflib.js/" target="_blank">Difflib.js</a> (for providing diffs)</li>
<li></li><a href="https://highlightjs.org/" target="_blank">highlight.js</a> (for code highlighting)</li>
</ul>
<br/>
<i>Please let me know if I forgot to mention your project.</i>



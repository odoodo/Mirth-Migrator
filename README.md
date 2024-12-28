<p align="center"><img src="https://github.com/user-attachments/assets/7d9cabf1-b4d2-4e32-8b40-3a579f398b72"></p>
<b>Mirth Migrator</b> is a web-based tool that automates the transfer of <a href="https://www.nextgen.com/solutions/interoperability/mirth-integration-engine/mirth-connect-downloads" target="_blank">Mirth Connect Enterprise Service Bus</a> channels and code templates between Mirth environments. It has a strong focus on JavaScript functions stored in code templates, ensuring seamless migration and proper function integration with the channels that depend on them. <br/>
<br/>
This tool works with the Open Source as well as the commercial version of Mirth Connect.

<h2>Key Features</h2>
<ul>
  <li><b>Transfer Channels and Code Templates</b><br/>Migrates channels and associated code templates between environments with ease, maintaining consistency across systems. </li>
  <li><b>Function-Dependency Analysis</b><br/>Detects and maps dependencies between JavaScript functions in code templates and their usage in channels or other functions.
Identifies which channels and functions rely on specific functions, as well as where functions are used. </li>
  <li><b>Conflict Handling</b><br/>Resolves version conflicts during migration, ensuring compatibility between the source and target environments. </li>
  <li><b>Inconsistency Detection</b><br/>Analyzes channels and code templates to uncover inconsistencies or missing dependencies that could cause errors post-migration. </li>
  <li><b>Environment Comparison</b><br/>Allows for a detailed comparison of channels and functions between environments, helping maintain synchronization. </li>
<li><b>Transparency in Function Usage</b><br/>Provides clear insights into which JavaScript functions are utilized by specific channels or other functions, aiding debugging and development.</li>
</ul>

<h2>Use Cases</h2>
  <ul>
  <li><b>Environment Synchronization</b><br/>Maintain consistent code and channel configurations across development, testing, and production environments. </li>
  <li><b>Conflict Resolution</b><br/>Identify and handle mismatched versions of functions or channels during migrations. </li>
  <li><b>Code Audit and Optimization</b><br/>Detect unused functions or redundant code within the environment for optimization.<br/>Also detect missing function or function references in channels & functions, which will result in runtime errors.</li>
  <li><b>Dependency Mapping</b><br/>Gain a better understanding of how functions and channels interact, crucial for large or complex Mirth Connect installations. </li>
  </ul>
  
<h2>Screenshots</h2>
  <kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/01fb4697-c3b1-4927-83b5-3880f2cad292" width="160"></kbd>
  <kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/582d4991-04b5-4132-805d-48aee2267c80" width="160"></kbd>
  <kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/0858502d-2135-4674-bdac-e784ab8fc1af" width="160"></kbd>
  <kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/d6752f34-d959-46cf-bbc8-e744b9284a47" width="160"></kbd>
  <kbd><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/9ba37e59-896c-4fb0-897c-2f133880d82f" width="160"></kbd>

<h2>Installation</h2>

<b>Mirth Migrator runs locally as a Mirth channel</b> - all your data remains internally in your organization.

<table>
  <tr><td><b>1.</b></td><td>Place the "web" folder in your "Mirth Connect" folder</td></tr>
  <tr><td><b>2.</b></td><td>Reference MirthMigrator.jar that you find at ./web/MirthMigrator/jar/ as custom resource</td></tr>
  <tr><td><b>3.</b></td><td>Import the MIRTH_MIGRATOR channel and reference the custom resource</td></tr>
  <tr><td><b>4.</b></td><td>Deploy the MIRTH_MIGRATOR channel</td></tr>
  <tr><td><b>5.</b></td><td>Browse to $\textsf{\color{blue}{http://&lt;YourMirthServer&gt;:1339/mirthMigrator}}$</td></tr>
</table><br/>

When you access Mirth Migrator, it asks you to <b>log in</b>. Simply <b>use your Mirth account</b> of the Mirth instance at which the MIRTH_MIGRATOR channel is running.

<h2>Kudos</h2>
Mirth Migrator makes use of a number of other open source projects:
<ul>
<li><a href="https://github.com/stleary/JSON-java" target="_blank">JSON in Java</a> (A JSON implementation for Java)</li>
<li><a href="https://github.com/qiao/difflib.js/" target="_blank">Difflib.js</a> (for providing diffs)</li>
<li><a href="https://highlightjs.org/" target="_blank">highlight.js</a> (for code highlighting)</li>
<li><a href="https://www.freepik.com/" target="_blank">Freepik</a> (for background image)</li>
</ul>



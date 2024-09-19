



<p align="center"><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/13eb2ff1-8386-41c3-8c1b-aba7127b112a" width="200"></p>
<b>Mirth Migrator</b> automates the transfer of <a href="https://www.nextgen.com/solutions/interoperability/mirth-integration-engine/mirth-connect-downloads" target="_blank">Mirth Enterprise Service Bus</a> channels and code templates from one instance to another.
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

<b>Mirth Migrator runs locally as a Mirth channel</b> - all your data remains internally in your organization.

<table>
  <tr><td><b>1.</b></td><td>Place the "web" folder in your "Mirth Connect" folder</td></tr>
  <tr><td><b>2.</b></td><td>Reference MirthMigrator.jar that you find at ./web/MirthMigrator/jar/ as custom resource</td></tr>
  <tr><td><b>3.</b></td><td>Import the MIRTH_MIGRATOR channel and reference the custom resource</td></tr>
  <tr><td><b>4.</b></td><td>Configure Mirth Migrator</td></tr>
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



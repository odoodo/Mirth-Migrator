
<p align="center"><img src="https://github.com/odoodo/Mirth-Migrator/assets/61003874/71503553-2554-4e7c-8ec1-6de1ec953973" width="200"></p>
A tool that automates the migration of <a href="https://www.nextgen.com/solutions/interoperability/mirth-integration-engine/mirth-connect-downloads" target="_blank">Mirth Enterprise Service Bus</a> channels and code templates from one instance to another.
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
<ol>
<li>Copy the folder "web" to your Mirth installation (to "&lt;Your Install Path&gt;\Mirth Connect")</li>
<li>Open the menu item "Settings", there the tab "Resources" and press "Add Resources"</li>
<li>Enter "./web/MirthMigrator/jar" in the field Directory and name the new Resource "MirthMigrator"</li>
<li>Save the change and press "Reload Resource". Depending on your Mirth version it should now show something like "&lt;Your Installation Path&gt;/Mirth%20Connect/web/MirthMigrator/jar/MirthMigrator.jar" or just "MirthMigrator.jar" under "Loaded Libraries"</li>
<li>Now go to menu item "Channels" and press "Import Channel"</li>
<li>Import MIRTH_MIGRATOR.xml</li>
<li>Within the MIRTH_MIGRATOR-channel click on tab "Summary" (1st tab) and press "Set Dependencies"</li>
<li>Choose the tab "Library Resources" (2nd tab) in the up-popping dialog and activate the checkbox for "Mirth Migrator"</li>
<li>Press ok, save the channel changes and deploy the channel</li>
</ol>
<h2>Configuration</h2>
Mirth Migrator does not yet feature a graphical configuration frontend. (might be added at a later point of time)
Thus, the configuration has to be changed in the configuration file directly.<br/>
<br/>
This configuration file can be found at: "&lt;Your Installation Path&gt;/Mirth%20Connect/web/MirthMigrator/config/<b>MirthMigrator.conf</b>"<br/>
<br/>
The configuration consists of 4 sections:
<ul>
  <li><b>sessionLifeSpanInMinutes</b><br/>Allows to determine the maximum inactivity period for a user session in minutes. If the value is 0, the user session will not expire.</li>
<li><b>environment</b><br/>Defines the environments to which the Mirth system can be assigned. The default configuration includes the following environments:<ul><li>Production</li><li>Test</li><li>Development</li></ul>This can be changed, of course, and an arbitrary number of environments can be added.</li>
<li><b>system</b><br/></li>
<li><b>excludeFromFunctionDetection</b><br/></li>
</ul>


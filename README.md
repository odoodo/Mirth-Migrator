
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

<h2>Installation</h2>

<h2>Configuration</h2>

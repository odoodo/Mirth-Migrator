  // if the configuration part was not yet added
  if (!$('#myDiv').length) {
	// do so
	$('body').append(	'<div class="openSettingsIcon" onclick="loadSettings();"></div>\n' +
						'<div id="configurationSection">\n' +
						'	<div class="closeSettingsIcon" onclick="saveSettings();" title="Close the settings sections.\nAll changes will be saved automatically."></div>\n' +
						'	<h1>&nbsp;&nbsp;Mirth Migrator Configuration</h1>\n' +
						'	<div class="tab">\n' +
						'		<button class="tablinks" id="environmentConfigurationTab" onclick="showConfigTab(\'environmentConfiguration\')" active>Environments</button>\n' +
						'		<button class="tablinks" id="systemConfigurationTab" onclick="showConfigTab(\'systemConfiguration\')">Mirth Instances</button>\n' +
						'		<button class="tablinks" id="functionFilterConfigurationTab" onclick="showConfigTab(\'functionFilterConfiguration\')">Filters</button>\n' +
						'		<button class="tablinks" id="miscConfigurationTab" onclick="showConfigTab(\'miscConfiguration\')">Misc</button>\n' +
						'	</div>\n' +
						'	<div id="intitialConfigInfo" class="configInfo">\n' +
						'<b>Please configure the information for all Mirth servers you want to access.</b> Every Mirth server needs a user account that can be used by Mirth Administrator.<br/>' + 
						'It is recommended to create an account like "service" or "MirthMigrator" that is easily identifiable as system account. <b>Press the close button <img src="/img/closeSettings.png" width="12"> in the upper right corner when done.</b>' +
						'	</div>\n' +
						'	<div id="environmentConfiguration" class="configurationTab">\n' +
						'	</div>\n' +
						'	<div id="systemConfiguration" class="configurationTab">\n' +
						'	</div>\n' +
						'	<div id="miscConfiguration" class="configurationTab">\n' +
						'	</div>\n' +
						'	<div id="functionFilterConfiguration" class="configurationTab">\n' +
						'	</div>\n' +
						'</div>\n'
	);
  }

var loadedConfigChecksum = null;
var configSectionActive = false;

/**
Taken from https://stackoverflow.com/questions/105034/how-do-i-create-a-guid-uuid
*/
function getUUID() {
  return "10000000-1000-4000-8000-100000000000".replace(/[018]/g, character =>
    (+character ^ crypto.getRandomValues(new Uint8Array(1))[0] & 15 >> +character / 4).toString(16)
  );
}

/**
Taken (and adapted) from https://stackoverflow.com/questions/7616461/generate-a-hash-from-string-in-javascript
*/
function calculateChecksum(jsonObject) {
	var string = JSON.stringify(jsonObject);
	const seed = 3;
    let h1 = 0xdeadbeef ^ seed, h2 = 0x41c6ce57 ^ seed;
    for(let position = 0, character; position < string.length; position++) {
        character = string.charCodeAt(position);
        h1 = Math.imul(h1 ^ character, 2654435761);
        h2 = Math.imul(h2 ^ character, 1597334677);
    }
    h1  = Math.imul(h1 ^ (h1 >>> 16), 2246822507);
    h1 ^= Math.imul(h2 ^ (h2 >>> 13), 3266489909);
    h2  = Math.imul(h2 ^ (h2 >>> 16), 2246822507);
    h2 ^= Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  
    return 4294967296 * (2097151 & h2) + (h1 >>> 0);
};

function loadSettings(tab, initial){
	// indicate that the config section is currently active
	configSectionActive = true;
	parameters = (tab || initial) ? {} : null;
	// if a tab was specified
	if(tab){
		// make sure to open this tab
		parameters.tab = tab;
	}
	// if it is the initial configuration
	if(initial){
		// display a support message
		parameters.displayMessage = true;
	}

	// load configuration and open config section
	accessResource('/getConfiguration', null, openSettings, parameters);
}

/**
Closes the settings panel and saves the configuration to disk, if it has changed
*/
function saveSettings(){
	
	// read the configuration from the settings tabs
	var configuration = generateConfiguration();
	// check if the configuration has changed
	var configurationHasChanged = (loadedConfigChecksum != configuration.checksum);
	// reset the marker
	loadedConfigChecksum = null;
	// remove the checksum from the configuration
	delete configuration.checksum;
		
	// only if the configuration has changed
	if(configurationHasChanged){
		// save the changes and force the active users to restart their session
		accessResource('/setConfiguration', configuration, closeSettings, {'reloadSystemList':true});
	} else{
		// nothing has been changed. Only close the screen
		closeSettings();
	}
}

function closeSettings(statusCode, returnValue, reloadSystemList){
	$("#configurationSection").css('display','none');
	$('#intitialConfigInfo').css('display', 'none');
	
	// remove active flag from all tabs
	$('.tablinks').each(function() {
		$(this).removeClass("active");
	});
	// if the configuration has changed
	if(reloadSystemList){
		// reload the systems list. This will trigger the "system configuration has changed" popup
		accessResource('/getSystems', null, setSystems);
	}
	// indicate that the config section is now deactivated
	configSectionActive = true;

}

function setEnvironmentConfiguration(environmentConfiguration){
	// create a container for the environments
	var environments = new Map();
	// add all environments to the map
	environmentConfiguration.forEach((environment) => {
		environments.set(environment.position, environment);
	});
	// now order the entries
	environments = new Map([...environments.entries()].sort());
	
	// build up the environment configuration table
	var environmentConfigurationSection = '<h2><i><u>Mirth Environments</u></i></h2><table id="environmentTable"><tr><th>Name</th><th>Color</th><th></th></tr>';
	// add all environments in the right order
	for (let [orderIndex, environment] of environments) {
		environmentConfigurationSection += '<tr><td><input type="text" id="' + environment.id + '" value="' + environment.name + '" title="The name of the environment"></td><td><input type="color" id="' + environment.id + '" name="environmentColor" value="' + environment.color + '" title="The color in which this environment and all mirth instances assigned to this environment will be displayed"></td><td class="deleteRow"><img src="/img/removeRow.png" alt="remove row" id="removeConfigRow" title="Remove this environment from the list"></img></td></tr>';
	}
	environmentConfigurationSection += '</table><br/>';
	// add a button for entering a new environment
	environmentConfigurationSection += '<button type="button" onclick="addEnvironment()" title="Adds a new environment.\nThe order of environment definitions also determines the order in which the environments are displayed in the application.">Add</button>';
	
	// and display the table
	$("#environmentConfiguration").html(environmentConfigurationSection);
	
	// finally arm the remove buttons of the configuration tables
	$(document).off('click', '#removeConfigRow').on('click', '#removeConfigRow', function() {
	//	alert('removing row!');
		// Find the parent row of the clicked remove icon and remove it
		$(this).closest('tr').remove();
	});
}

/**
 * Creates a JSON array from the configuration table for the Mirth environments
 * @return {Object[]} a JSON array containing Mirth environment definitions
 */
function getEnvironmentConfiguration(){
	var environments = [];
	var positionId = 1;
	
	// read all environment configurations from the table
	$('#environmentTable tr:not(:first-child)').each(function() {

		// if the name of the current environment is set
		var currentField = $(this).find('td').eq(0).find('input');
		if(currentField.val().trim()){
			var environment = {};

			// get the id
			var id = currentField.attr('id');
			// the name
			var name = currentField.val().trim();
			// the second cell contains the color
			currentField = $(this).find('td').eq(1).find('input');
			// get the color that represents the environment
			var color = currentField.val();
			// determine the position of this environment in the environment list
			var position = positionId++;
			// add the environment to the list
			environments.push({
								"color": color,
								"name": name,
								"id": id,
								"position": position
			});
		}
	});
	
	return environments;
}

/**
 * Creates a JSON array from the configuration table for the Mirth instances
 * @param {boolean} encryptPasswords If this flag is set, the passwords will be encrypted
 * @return {Object[]} a JSON array containing Mirth instance definitions
 */
function getSystemConfiguration(encryptPasswords){
	var systems = [];
	
	// read all environment configurations from the table
	$('#mirthInstanceTable tr:not(:first-child)').each(function() {

		// if the name of the current environment is set
		var currentField = $(this).find('td').eq(0).find('input');
		if(currentField.val().trim()){
			// get the id
			var name = $(this).find('td').eq(0).find('input').val().trim();
			var description = $(this).find('td').eq(1).find('input').val().trim();
			var server = $(this).find('td').eq(2).find('input').val().trim();
			var port = +$(this).find('td').eq(3).find('input').val();
			var user = $(this).find('td').eq(4).find('input').val().trim();
			var password = $(this).find('td').eq(5).find('input').val().trim();
			if(encryptPasswords){
				password = encrypt(password);
			}
			var environment = $(this).find('td').eq(6).find('#environment').val();

			// add the Mirth instance to the list
			systems.push({
				"server": server,
				"environment": environment,
				"password": password,
				"port": port,
				"name": name,
				"description": description,
				"user": user
			});
		}
	});
	
	return systems;
}

/**
 * Creates a JSON array from the configuration table for the function filters
 * @return {Object[]} an array containing the function filter definitions
 */
function getFunctionFilterConfiguration(){
	var functionFilters = [];
	
		$('#functionFiltersTable tr').each(function() {
			functionFilters.push($(this).find('td').eq(0).text().trim());
		});
	
	return functionFilters;
}

/**
 * Creates a JSON array from the parameters of the miscellaneous section
 * @return {Object} A json object containing the content of the misc section
 */
function getMiscConfiguration(){
	// use the current value of the channel status refresh rate
	refreshIntervalInSeconds = $('#channelStatusUpdateInterval').val().trim();
	activateChannelStatusUpdates();
	
	// set the channel state control scheme that should be used
	useExtendedChannelStateControlScheme = $('#useExtendedChannelStateControlScheme').is(":checked");
	
	// Read in the misc parameters. The order is of importance as the Java side stores the config in exaclty this order (no clue why). 
	// If the order is changed the checksums do not correspond anymore and the config will be detected as changed everytime the section is closed
	return  {
				"sessionLifeSpanInMinutes": ($('#sessionLifespann').val().trim() != 'deactivated') ? $('#sessionLifespann').val().trim() : 0,
				"useExtendedChannelStateControlScheme": useExtendedChannelStateControlScheme,
				"channelStatusUpdateIntervalInSeconds": refreshIntervalInSeconds
			}
}

/**
 * Generates the configuration
 * @return The configuration as JSON object
 */
function generateConfiguration(){
	var configuration ={};
	
	configuration.environment = getEnvironmentConfiguration();
	// set it once w/o encrypted passwords for calculating the checksum
	configuration.system = getSystemConfiguration();
	configuration.miscellaneous = getMiscConfiguration();
	configuration.excludeFromFunctionDetection = getFunctionFilterConfiguration();
	// calculate the checksum of the plain config
	configuration.checksum = calculateChecksum(configuration);
	// now the passwords can be encrypted w/o altering the checksum
	configuration.system = getSystemConfiguration(true);
	
	return configuration;
}


/**
 * Creates a select box containg all available Mirth environoments
 * @param {String} environments An array containing all environments
 * @return {String} a select box containing the available Mirth environments
 */
function createEnvironmentSelect(environments){
	
	// if the environments were not provided
	if(!environments){
		// fetch them from the config screen
		environments = getEnvironmentConfiguration();
	}
	
	// assemble the environment select box
	var environmentSelect = '<select id="environment">\n';
	environments.forEach((environment) => {
		// add each environment with it's color
		environmentSelect += '<option style="background-color: ' + environment.color + ';" color="' + environment.color + '" value="' + environment.id + '">' + environment.name + '</option>\n';
	});
	environmentSelect += '</select>\n';

	return environmentSelect;
}

/**
 * Creates the content of the Mirth Intances panel 
 * @param {Object[]} systemConfiguration An array containing all Mirth instances
 * @param {Object[]} environments An array containing all environments
 */
function setSystemConfiguration(systemConfiguration, environments){
	
	// assemble the environment select box
	var environmentSelect = createEnvironmentSelect(environments);
	
	// build up the Mirth instance configuration table
	var mirthInstanceConfigurationSection = '<h2><i><u>Mirth Instances</u></i></h2><table id="mirthInstanceTable"><tr><th>Name</th><th>Description</th><th>Server</th><th>Port</th><th>User</th><th>Password</th><th>Environment</th><th></th></tr>';
	// add all Mirth instances to the table
	systemConfiguration.forEach((system) => {
		
	mirthInstanceConfigurationSection  +=	'<tr><td><input type="text" id="name" value="' + system.name + '" title="The display name of the mirth instance"></td>' +
											'<td><input type="text" id="description" value="' + system.description + '" title="An optional description about what this mirth instance is for"></td>' +
											'<td><input type="text" id="server" value="' + system.server + '" title="the server name or IP of the mirth instance"></td>' +
											'<td style="width: 50px;"><input type="number" id="port" value="' + system.port + '" title="The port of the mirth instance" style="width: 42px"></td>' +
											'<td><input type="text" id="user" value="' + system.user + '" title="The user name of a mirth user account at this mirth instance.\nThis account needs full access."></td>' +
											'<td><input type="password" id="password" value="' + system.password + '" title="The passord of a mirth user account at this mirth instance.">&nbsp;<img src="/img/showPassword.png" alt="show password" id="showPassword" onmouseenter="showPassword(event)" onmouseleave="hidePassword(event)" onclick="copyToClipboard(event)" title="Click left to copy the password to the clipboard"></img></td>' +
											'<td title="The environment to which this mirth instance belongs">' + environmentSelect.replace('value="'+system.environment + '"', 'value="'+system.environment + '" selected') + '</td>' +
											'<td class="deleteRow"><img src="/img/removeRow.png" alt="remove row" id="removeConfigRow" title="Remove the mirth instance definition"></img></td></tr>';
	});
	mirthInstanceConfigurationSection += '</table><br/>';
	// add a button for entering a new Mirth instance
	mirthInstanceConfigurationSection += '<button type="button" onclick="addMirthInstance()" title="Adds another mirth instance.">Add</button>';
	
	// and display the table
	$("#systemConfiguration").html(mirthInstanceConfigurationSection);
	$("#systemConfiguration").css("display", "none");
		
	// finally arm the remove buttons of the configuration tables
	$(document).off('click', '#removeConfigRow').on('click', '#removeConfigRow', function() {
	//	alert('removing row!');
		// Find the parent row of the clicked remove icon and remove it
		$(this).closest('tr').remove();
	});
	
	activateSelectionColorChanger();
}

function showPassword(event){
	$(event.target).closest('td').find('input').attr('type', 'text');
}

function hidePassword(event){
	$(event.target).closest('td').find('input').attr('type', 'password');
}

function copyToClipboard(event){
	var content = $(event.target).closest('td').find('input').val();
	var temp = $('<textarea>').appendTo('body').val(content).select();
	document.execCommand('copy');
	temp.remove();
	
	// provide user feedback about the action by flashing the copied element
	$(event.target).addClass("flashOnCopyContent");

	setTimeout(function() {
		// after a delay of 200ms switch back to the original background color
		$(event.target).removeClass("flashOnCopyContent");
	}, 300);
}

/**
 * Creates the miscellaneous section of the configuration panel 
 * @param {Object[]} miscellaneous A JSON object containing all parameters of the misc section
 */
function setMiscConfiguration(miscellaneous){
	// create the section content
	var miscConfiguration = '<h2><i><u>Misc Configuration Parameters</u></i></h2>' + 
							'<table id="miscParameters">' + 
							'<tr><td>Maximum inactivity period:</td><td style="white-space: nowrap; overflow: hidden; text-overflow: ellipsis;"><input type="number" id="sessionLifespann" min="0" value="' + (miscellaneous.sessionLifeSpanInMinutes || 0) + '" style="width: 40px" title="The number of minutes of inactivity after which a user session will expire and the user needs to reauthenticate to go on.\nThe session will never expire if this value is set to 0."> minutes&nbsp;<span id="unlimitedLifespan" style="color: red" title="The session inactivity timeout is currently deactivated.\nIncrease the value for reactivating it."/><b><i>(deactivated)</i></b></span></td></tr>' + 
							'<tr><td>Channel status refresh frequency:</td><td><input type="number" id="channelStatusUpdateInterval" min="1" value="' + (miscellaneous.channelStatusUpdateIntervalInSeconds || 5) + '" style="width: 40px" title="The number of seconds after which the state of channels will be refreshed"/> seconds</td></tr>' + 
							'<tr><td>Use extended channel state control:</td><td><input type="checkbox" id="useExtendedChannelStateControlScheme"' + (miscellaneous.useExtendedChannelStateControlScheme ? ' checked="checked"' : '') + '" title="Determines if the extended channel state control scheme or the classic one like in Mirth Administrator will be used.\n\nThe extended channel state control scheme allows the direct change between arbitrary states (e.g. from stopped to paused).\n\nThis however comes at a price:\nIf a channel should be set from STOPPED to PAUSED or should be deployed to an arbitrary state that is not configured as the initial state of the channel, the Mirth Administrator will indicate a revision change.\n\nThis is due to the way the limitations are circumvented:\n 1. initial state is changed to the desired state\n 2. channel is deployed\n 3. initial state is changed back to the original state, which causes the revision change"/></td></tr>' + 
							'</table>';
	// and display it
	$("#miscConfiguration").html(miscConfiguration);
	$('#unlimitedLifespan').css('display', (miscellaneous.sessionLifeSpanInMinutes === "0") ? 'inline-block' : 'none');
	$("#miscConfiguration").css("display", "none");

	$('#sessionLifespann').on('input', function() {
		if($(this).val() <= 0){
			$(this).val(0);
		}
		$('#unlimitedLifespan').css('display', ($(this).val() === "0") ? 'inline-block' : 'none');
	});
	
	$('#channelStatusUpdateInterval').on('input', function() {
		if($(this).val() < 1){
			$(this).val(1);
		}
	});
}

function setFunctionFilterConfiguration(functionFilters){

	// create the section content
	var functionFilterConfiguration = 	'<h2><i><u>Function Filters</u></i></h2><table id="functionFilterContainer">\n' + 
										'<tr><th><input type="text" id="filter" onkeydown="handleKeyDownEvent(event)" oninput="filterFunctionFilters(this.value)" title="Enter a search string for a function filter or the name of a new function to filter.">&nbsp;<button id="addFilterButton" type="button" onclick="addFilter()" title="Add this function name to the list.\nIf the function detection algorithm detects a function reference with this name, it will ignore it.\n\nBe aware that your input is case sensitive when used by the function filter algorithm.\n\nThis button is only available if the entered function name is not already in the list.">Add</button></th></tr>\n' + 
										'<tr><td><hr></td></tr>' +
										'<tr><td id="functionFilterWrapper"><table id="functionFiltersTable">\n';
	// order filter names descending								
	functionFilters.sort(function (a, b) {return a.toLowerCase().localeCompare(b.toLowerCase());});
	// and show them in the table
	functionFilters.forEach(function(functionName) { 
		functionFilterConfiguration += '<tr><td filter="' + functionName.toLowerCase() + '">' + functionName + '</td><td class="deleteRow"><img src="/img/removeRow.png" alt="remove row" id="removeConfigRow"></img></td></tr>'
	});
	functionFilterConfiguration += '</table></td></tr></table>';
	
	$("#functionFilterConfiguration").html(functionFilterConfiguration);
	$("#functionFilterConfiguration").css("display", "none");
	$('#addFilterButton').prop('disabled', true);
}

function filterFunctionFilters(value){
	var alteredValue = value.replace(/ /g, '');
	if(alteredValue.length != value.length){
		 $('#filter').val(alteredValue);
		return;
	}
	
	var filterValue = value.toLowerCase();
	var disableAddButton = !value;
	
	$('#functionFiltersTable tbody tr').each(function() {
		var name = $(this).find('td').text();

		var filter = $(this).find('td').attr('filter');
		if(filter.indexOf(filterValue) > -1){
			$(this).show();
			if(value == name){
				disableAddButton = true;
			}
		} else{
			$(this).hide();
		}
	})
	
	 $('#addFilterButton').prop('disabled', disableAddButton);
}

function handleKeyDownEvent(event){
	if((event.key === 'Enter')&& !$('#addFilterButton').prop('disabled')){
		addFilter();
	}

	return false;	
}

function addFilter(){

	// get the new filter value
	var newFilter = $('#filter').val();
	var newRow = '<tr class="newTableRowFade newTableRowHighlight"><td filter="' + newFilter.toLowerCase() + '">' + newFilter + '</td><td class="deleteRow"><img src="/img/removeRow.png" alt="remove row" id="removeConfigRow"></img></td></tr>';
	
	var jobDone = false;
	$('#functionFiltersTable tbody tr').each(function() {
		// get the value of the current row
		var currentFilter = $(this).find('td').first().text();

		// check if should be alphabetically located before the current row
		if (newFilter.toLowerCase() <= currentFilter.toLowerCase()) {
			// Insert new filter before the current one
			$(newRow).insertBefore($(this));
			jobDone = true;
			return false;
		}
	});

	// If the new row is not inserted, append it at the end
	if (!jobDone) {
		$('#functionFiltersTable').append(newRow);
	}
	
	// make the highlight fade after 10 seconds
	setTimeout(function() {
		$('#functionFiltersTable tbody tr.newTableRowFade').removeClass('newTableRowHighlight');
	}, 10000);
	
	$('#filter').val('');
	filterFunctionFilters('');
}

function openSettings(requestStatus, configuration, parameters){
	
	// indicate that the config section is currently active
	configSectionActive = true;
	// create a checksum from the loaded settings for detecting later on if configuration has changed
	loadedConfigChecksum = calculateChecksum(configuration);
	
	$("#configurationSection").css('display','block');
	$("#environmentConfiguration").css('display','block');
	$("#systemConfiguration").css('display','block');
	$("#miscConfiguration").css('display','block');
	$("#functionFilterConfiguration").css('display','block');

	// and activate the default tab
	$('#environmentConfigurationTab').addClass("active");
	
	/** Environment section **/
	setEnvironmentConfiguration(configuration.environment);
		
	/** Mirth Instances section **/
	setSystemConfiguration(configuration.system, configuration.environment || getEnvironmentConfiguration());
	
	/** Filter section **/
	setFunctionFilterConfiguration(configuration.excludeFromFunctionDetection);
	
	/** Misc section **/
	setMiscConfiguration(configuration.miscellaneous);

	// if a specific tab should be initially shown
	if(parameters){
		// activate it
		showConfigTab(parameters.tab, parameters.displayMessage);
	}
}

function addEnvironment(){
	var environmentId = getUUID();
	var newRow = '<tr><td><input type="text" id="' + environmentId + '"></td><td><input type="color" id="' + environmentId + '" name="environmentColor"></td><td class="deleteRow"><img src="/img/removeRow.png" alt="remove row" id="removeConfigRow"></img></td></tr>';
	$('#environmentTable').append(newRow);
}

function addMirthInstance(environments){
	var mirthInstanceId = getUUID();
	var newRow = '<tr><td><input type="text" id="name" value=""></td>' +
				'<td><input type="text" id="description" value=""></td>' +
				'<td><input type="text" id="server" value=""></td>' +
				'<td><input type="number" id="port" value="8443" style="width: 42px"></td>' +
				'<td><input type="text" id="user" value=""></td>' +
				'<td><input type="password" id="password" value="">&nbsp;<img src="/img/showPassword.png" alt="show password" id="showPassword" onmouseenter="showPassword(event)" onmouseleave="hidePassword(event)" onclick="copyToClipboard(event)"></img></td>' +
				'<td>' + createEnvironmentSelect(environments) + '</td>' +
				'<td class="deleteRow"><img src="/img/removeRow.png" alt="remove row" id="removeConfigRow"></img></td></tr>';
	$('#mirthInstanceTable').append(newRow);
	
	// activate highlighting of the selected option
	activateSelectionColorChanger();
}

function showConfigTab(tabName, displayMessage) {

	// remove active flag from all tabs
	$('.tablinks').each(function() {
		$(this).removeClass("active");
	});

	// and add it to the current one
	$('#' + tabName + 'Tab').addClass("active");

	// now disable all configuration sections
	$('.configurationTab').each(function() {
		$(this).css("display", "none");
	});

	// Mirth instance configuration tab needs some extra love
	if(tabName == 'systemConfiguration'){

		// determine if initial configuration info message should be displayed
		$('#intitialConfigInfo').css('display', displayMessage ? 'block' : 'none');
		// assemble the environment select box
		var environmentSelect = createEnvironmentSelect();
		
		// for all Mirth instance entries
		$('#mirthInstanceTable tr:not(:first-child)').each(function() {
			// get the currently selected environment
			var environmentId = $(this).find('td').eq(6).find("select").val();
			// replace the select box with an up to date version
			$(this).find('td').eq(6).html(environmentSelect.replace('value="' + environmentId + '"', 'value="' + environmentId + '" selected'));
		});
	} else{
		// well, this is a little bit slack might later on be expanded to a help system for all items
		$('#intitialConfigInfo').css('display', 'none');
	}
	
	// make it fancy by displaying the color of the choosen environment
	activateSelectionColorChanger();
	
	// and finally show the section of the configuration that corresponds to the clicked button
	$('#' + tabName).css('display','block');
}

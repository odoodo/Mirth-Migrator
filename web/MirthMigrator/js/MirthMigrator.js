var sessionId = null;

/**
 * These variables are used to detect if a section is still hidden or not.
 */
var sourceBoxShown  = false;
var destBoxShown = false;
var componentTypeIsSelected = false;
var metaDataTableShown = false;

/**
 * The last clicked and highlighted component and if it has a correspondance on the other system also the corresponding ID
 */
var selectedId;
var selectedCorrespondanceItemId;
/**
 * Two lists are necessary to keep track of a components id and and its corresponding type. The corresponding names are saved
 * to give the user feedback about which components were migrated.
 */
var componentMigrationListTypes = [];
var componentMigrationListNames = [];
var componentToMigrate = new Map();
var componentWithConflict = [];
var componentsToSkip = [];

/**
 * This variables contain the content of the compared components.
 */
var currentSourceComponent;
var currentDestComponent;
/**
 * These variables are necessary when the conflict compare is shown or refreshed. They contain the displayed source code 
 * 
 */
var currentSourceComponentContent;
var currentDestinationComponentContent;
/**
 * The contextSize is initialised with 1 and afterwards can switch between the values 1 and null.
 * It gives information about how to display the components conflict.
 */
var contextSize = 1;
/**
 * Determines how a diff is displayed:
 * - 0: side by side mode
 * - 1: inline mode
 */
var displayMode = 1;
/**
 * These variables hold the amount of groups for each system, to calculate the number of subcomponents.
 */
var numberOfSourceGroups;
var numberOfDestGroups;
/**
 * This variable indicates if the migration happens from source to dest or dest to source
 */
var sourceToDest = true;
var selectedGroupIDs = [];
var selectedGroupType;
var lastHighlighted;

var activityCounter = 0;

/**
* Temporarily stores the request & payload if a login action is required before the request can be placed
*/
var pendingRequests = [];



/**
 * Starts an activity and replaces the normal mouse cursor by the busy mouse cursor
 */
function indicateActivityStart(){
	// indicate activity to the user if not already done (for another ongoing activity)
	if(!activityCounter){
		$("*").css("cursor", "inherit");
		$(document.body).css("cursor", "wait");
    }
	
	// add the current activities to the ongoing tasks
	activityCounter = activityCounter + 1;
}

/**
 * Completes an ongoing activity and replaces the busy mouse cursor by the normal mouse cursor if all tasks are completed
 */
function indicateActivityEnd(){
	// remove the current activities from the ongoing tasks
	activityCounter--;

	// indicate reomve activity indicator if all ongoing activities have been completed
	if(activityCounter < 1){
		// reset the cursor
		$(document.body).css("cursor", "default");
		// and make sure, the counter is set to 0
		activityCounter = 0;
    }
}

/**
 * Changes the content of a select-box in correspondance to a selected system
 * @param {*} systemType either <b>sourceSystem</b> for the left select box or <b>destSystem</b> (well, acually anything else then sourceSystem) for the right selectbox
 */
function systemChanged(systemType){

	// get the identifier of the system that has been set
	var system = $('#' + systemType + ' option:selected').attr('id');
	// and also it's display name
	var systemName = $('#' + systemType + ' option:selected').val();
	// re-enable all siblings of the currently selected item
    $("#" + systemType + " option[id='" + system + "']").attr("disabled", "disabled").siblings().removeAttr("disabled");
	
	// if system selection was removed
	if(system == 'none'){
		// depending on the chosen box	
		if(systemType == 'sourceSystem'){
			// hide the left box
			$('#componentTableLeft').css('visibility', 'hidden');
			sourceBoxShown = false;	
		} else{
			// hide the right box
			$('#componentTableRight').css('visibility', 'hidden');
			destBoxShown = false;	
		}
		
		// remove potential correspondance highlighting
		metaDataChecker();
		
		// as there is now 1 box left at most, also hide interaction buttons
		$('#migrateButton').css('visibility', 'hidden');
		$('#compareButton').css('visibility', 'hidden');
		// only hide refresh button if no box is shown anymore
		if(!sourceBoxShown && !destBoxShown){
			$('#flexBoxButtons').css('visibility', 'hidden');
		}
		
		// and also all component detail information. 
		// Improvement here would be to only hide them if the belong to the chosen box
		$('#preComponentContent').css('display', 'none');
		$('#contentHeader').css('visibility', 'hidden');
		$('#collapseContentIcon').css('visibility', 'hidden');
		$('#metaDataSection').css('display', 'none');
		$('#collapseMetaDataIcon').css('visibility', 'hidden');

		return;
	}
	

	//change the color of the selectbox to the color of the environment to which the system belongs
    changeSelectionColor(systemType);
	// displayes the list containing the components
    displayHiddenBoxes(systemType);
	// if a component type (code template or channel) was selected
    if(componentTypeIsSelected){
		// determine if code templates or channels was choosen
		var componentType = $('input[name = "compType"]:checked').val();
		var payload = {
						"componentType": componentType,
						"system": systemName,
						"refresh": false
					}
		
		// display the corresponding components in the table
		accessResource('/getComponentMetaData', payload, populateTable, {"systemType": systemType});
	}
}

/**
 * This function is called once the component type changed, when a radiobutton of channels or code Templates was clicked.
 * It can also be invoked when you want to refresh the two tables.
 * @param {*} refresh if this flag is set, the system data will be reloaded from the server
 */
function populateComponentTables(refresh) {

		//enables the title description in a jquery tooltip for all elements
	    //$( document ).tooltip();
		//The tables are displayed, when the component type has been selected.
		if(!componentTypeIsSelected) 
		{
			componentTypeIsSelected=true;
			$("#sourceSelection").css("visibility", "visible");
			$("#destSelection").css("visibility", "visible");
		}
		var componentType = $('input[name = "compType"]:checked').val();
		var payload;

		// if a system for the left box was choosen
		if(sourceBoxShown){
			payload = {
					"componentType": componentType,
					"system": $('#sourceSystem option:selected').val(),
					"refresh": refresh
				}
			// change the content of the left select-box
			accessResource('/getComponentMetaData', payload, populateTable, {"systemType": "sourceSystem"});
		}
		// also if a system for the right box was choosen
		if(destBoxShown){
			payload = {
					"componentType": componentType,
					"system": $('#destSystem option:selected').val(),
					"refresh": refresh
				}
			// change the content of the right box
			accessResource('/getComponentMetaData', payload, populateTable, {"systemType": "destSystem"});
		}
}

/**
 * Calls a webservice. The user is first forced to login if there is no valid session.
 * @param {*} command - the path of the webservice that should be called
 * @param {*} payload The payload that should be transferred to the server
 * @param {*} action The function that should be called w/ the response from the server
 * @param {*} parameters A json-object containing additional parameters that should be passed to the function defined under action
 * @param {*} refreshCache If set, the component metadata at server-side will be reloaded
 */ 
function accessResource(command, payload, action, parameters, refreshCache){
	// make sure all needed parameters are available
    if((!command || !action)){
		var message = (!command ? 'The webservice url':'The name of the function that should be called after execution') + ' is missing!';
		 console.log(message);
		 alert(message);
		 return;
	}
	
	// if there is not yet a session id
	if(!sessionId){
		// temporarily store the request till the login was completed successfully
		pendingRequests.push({
			"command": command,
			"payload": payload || {},
			"action": action,
			"parameters": parameters || '',
			"refreshCache": refreshCache || false});
		
		// and open the login dialog
		openLogin();
		return;
	}
	
	// activate mouse busy pointer
	indicateActivityStart();
	
	// make the ajax call
	$.ajax(
	{
		url: command,
		type: "POST",
		dataType: "json",
		contentType: 'application/json',
		data: JSON.stringify(payload || {}),
		beforeSend: function(xhr) {
			if(sessionId){
				xhr.setRequestHeader("sessionId", sessionId);
			}
		},
		success: function(response, status, xhr) {
			try{
				// get the status code
				statusCode = xhr.status;
				// and also the response object
				response = JSON.parse(xhr.responseText);
				// get the session id from the response header
				sessionId = xhr.getResponseHeader('sessionId');
				// finally call the follow-up function
				action(statusCode, response, parameters);
			} finally{
				// indicate to the user that the activity has ended (if there are no other ongoing task)
				indicateActivityEnd();
			}
		},
		error: function(xhr, status, error) {
			try{
				// get the status code
				statusCode = xhr.status;
				try{
				// and also the response object				
				response = JSON.parse(xhr.responseText);
				}catch(e){
					var message = xhr.responseText;
					if(!message){
						message = 'There seems to be a server-side Exception.\nPlease check MIRTH_MIGRATOR channel in Mirth Administrator.';
					}
					// set the error message text
					$("#errorInfo").html('GOT AN EXCEPTION WHEN PARSING ('+statusCode+' '+status+'): \n'+xhr.responseText);
					// and display the eror dialog
					$("#dialog").css('display','block');
					$("#errorPopup").css('display','flex');
					return;
				}
				
				// no, or invalid login or expired session
				if((statusCode == 401) || (statusCode == 440) || (statusCode == 400)){
					
					infoText = '';
					
					// temporarily store the request till the login was completed successfully
					pendingRequests.push({
						"command": command,
						"payload": payload || {},
						"action": action,
						"parameters": parameters || '',
						"refreshCache": refreshCache || false});
					
					if(sessionId && sessionId.trim().length){
						// set the info text for the login dialog depending on the event
						infoText = (statusCode == 401) ? "Invalid username or password" : "Session expired";
					}
					// open the login dialog
					openLogin(infoText);
					return;
				} else if(statusCode == 409){
					// configuration has changed
					$("#dialog").css('display','block');
					$("#reloadPopup").css('display','flex');

					return;
				} else if(statusCode == 500){
					// no config file
					if(!configSectionActive){
						// there is not yet a configuration file. Thus force configurator to appear. Open the Mirth Instances tab
						loadSettings('systemConfiguration', true);
					}
					return;
				}
				
				$("#errorImage").attr("src", ((statusCode == 503) ? '/img/serverUnavailable.png' : '/img/error.png'));
				// set the error message text
				$("#errorInfo").html(response + ' (' + statusCode + ' ' + status + ')');
				// and display the eror dialog
				$("#dialog").css('display','block');
				$("#errorPopup").css('display','flex');
			} finally{
				// indicate to the user that the activity has ended (if there are no other ongoing task)
				indicateActivityEnd();
			}
		}
	});
}

/**
 * Sets the list of available environments (legend in the upper right corner)
 * @param {*} statusCode - The return code of the ajax request
 * @param {*} response - The ajax response message containing the environments
 */ 
function setEnvironments(statusCode, response){
	// create an environment list
	var environments = $('<ul id="environments"></ul>');
	// fill it
	$.each(response, function(index, environment) {
		// with the configured environments
		environments.append('<li style="color:' + 
								environment.color + 
								'"><span style="background-color:' + 
								environment.color + 
								';">&#160;&#160;&#160;&#160;</span> ' + 
								environment.name + 
								'</li>');
	});
	// and set it on the page
	$("#environments").replaceWith(environments);
}

/**
 * Sets the content of the select boxes of Mirth Instance A & Mirth Instance B
 * @param {*} statusCode - The return code of the ajax request
 * @param {*} response - The ajax response message containing the mirth systems
 */ 
function setSystems(statusCode, response){

	// create the select box for choosing the first system
	var systemSelectA = $('<select id="sourceSystem" class="" onchange="systemChanged(this.id)"></select>');
	// create the select box for choosing the second system
	var systemSelectB = $('<select id="destSystem" class="" onchange="systemChanged(this.id)"></select>');
	// add a default entry to both select boxes
	systemSelectA.append('<option class="disabledOption" value="Please select a System" id="none" disabled="disabled" selected="selected">Please select a System</option>');
	systemSelectB.append('<option class="disabledOption" value="Please select a System" id="none" disabled="disabled" selected="selected">Please select a System</option>');

	// order systems by environment and name
	var orderedSystems = new Map();
	$.each(response, function(index, system) {
		// add all systems to the map
		orderedSystems.set(system.environmentOrderId + '_' + system.name, system);
	})
	var systemIdentifiers = Array.from(orderedSystems.keys()).sort();
	
	// and now add the actual systems to the select boxes
	$.each(systemIdentifiers, function(index, key) {
		var system = orderedSystems.get(key);
		// with the actual systems
		systemSelectA.append('<option title="' + 
							system.description + 
							'" id="' + 
							system.server +
							'" color="' +
							system.color +
							'" style="background-color:' + 
							system.color + 
							';font-weight: bold;" value="' + 
							system.name + 
							'">' + 
							system.name + 
							'</option>');
		systemSelectB.append('<option title="' + 
							system.description + 
							'" id="' + 
							system.server + 
							'" color="' +
							system.color +
							'" style="background-color:' + 
							system.color + 
							';font-weight: bold;" value="' + 
							system.name + 
							'">' + 
							system.name + 
							'</option>');
	});
	// and set it on the page
	$("#sourceSystem").replaceWith(systemSelectA);
	$("#destSystem").replaceWith(systemSelectB);
	
	activateSelectionColorChanger($("#sourceSystem"));
	activateSelectionColorChanger($("#destSystem"));
}

/**
 * Displays the Mirth Migrator version number
 */ 
function setVersion(statusCode, response) {
	// set the version number
	$("#versionNumber").html('v' + response.version);
}

/**
 * Displays a login dialog for entering username and password
 * @param {*} infoText - a message that will be displayed on the login dialog
 */ 
function openLogin(infoText) {
	// set the info text
	$("#loginInfo").html(infoText);
	// display the login dialog
	$("#dialog").css('display','block');
	$("#loginPopup").css('display','flex');
	
	// if the username field is empty
	if($('#username').val().trim() == ''){
		// set focus to the username input field
		$('#username').focus();
	} else{
		// if username was already entered, set focus to the password
		$('#userpassword').focus();
	}
}

$(document).ready(function() {

	/**
	Retries to load the original query after the login information have been entered if there was no valid session.
	*/
	$("#submit").click(function(event) {
		// avoid reloading the page
		event.preventDefault();
		// generate a token
		sessionId = encrypt($('#username').val() + ':' + $('#userpassword').val());

		// unshow the login form
		$("#dialog").css('display','none');
		$("#loginPopup").css('display','none');
		// and remove the password
		$('#userpassword').val('');
		
		// get the list of queued commands
		var requestList = pendingRequests;
		// and free the list of pending requests for new requests (which will appear if login was not correct)
		pendingRequests = [];
		
		// reprocess all queued requests
		requestList.forEach((request) => {
			// retry sending the original request - this time w/ potentially valid login information
			accessResource(request.command, request.payload, request.action, request.parameters, request.refreshCache);
		});
	});
	
	$("#confirmError").click(function(event) {
		// avoid reloading the page
		event.preventDefault();
		
		// remove the error dialog from screen
		$("#dialog").css('display','none');
		$("#errorPopup").css('display','none');
	});

	$("#confirmReload").click(function(event) {
		// avoid reloading the page
		event.preventDefault();
		
		// close all divs (besides the main window)
		$('#dialog').css('display', 'none');
		$('#migrationReport').css('display', 'none');
		$('#migrationIndicator').css('display', 'none');	
		$("#reloadPopup").css('display','none');
		
		// close all elements on the main window
		$('#preComponentContent').css('display', 'none');
		$('#contentHeader').css('visibility', 'hidden');
		$('#collapseContentIcon').css('visibility', 'hidden');
		$('#metaDataSection').css('display', 'none');
		$('#collapseMetaDataIcon').css('visibility', 'hidden');
		$('#migrationReport').css('visibility', 'hidden');
		$('#migrationIndicator').css('visibility', 'hidden');
		$('#componentTableLeft').css('visibility', 'hidden');
		$('#componentTableRight').css('visibility', 'hidden');
		$('#migrateButton').css('visibility', 'hidden');
		$('#compareButton').css('visibility', 'hidden');
		$('#flexBoxButtons').css('visibility', 'hidden');
		sourceBoxShown = destBoxShown = false;
	
		// reload environment list
		accessResource('/getEnvironments', null, setEnvironments);
		// and system list
		accessResource('/getSystems', null, setSystems);
	});
	
	$('#username').on('keydown', function(event) {
		if (event.key === "Enter") {
			event.preventDefault(); // Prevent default form submission behavior
			$('#userpassword').focus();
		}
	});
	$('#userpassword').on('keydown', function(event) {
		if (event.key === "Enter") {
			event.preventDefault(); 
			if(!$('#userpassword').val().trim() || !$('#username').val().trim()){
				$('#username').focus();
			}else{
				$('#submit').click();
			}
		}
	});	
	
	// if content of input fields has been changed
	$('#login-form').on('keyup', 'input', function() {		
		// disable or enable the submit button depending on the content of login & password
		var isDisabled = (!$('#userpassword').val().trim() || !$('#username').val().trim());
		$('#submit').prop('disabled', isDisabled);
		$('#submit').removeClass().addClass(isDisabled ? 'buttonDisabled' : 'buttonEnabled');
	});
	
	// display the Mirth Migrator version
	accessResource('/getVersion', null, setVersion);
	// make an initial call for populating some arreas as soon as the page was loaded
	accessResource('/getEnvironments', null, setEnvironments);
	// and now load the system select boxes
	accessResource('/getSystems', null, setSystems);
	
	activateSelectionColorChanger();
});

/**
 * Migrates the selected components from the source to the destination system. 
 * @param {String} sourceSystem The name of the system from which the compents should be migrated.
 * @param {String} destSystem The name of the system to which the compents should be migrated.
 */
function migrateComponents(returnCode, migratedComponents){

	// define arrays for creating sorted lists per type
	var channels = [];
	var codeTemplates = [];
	var channelGroups = [];
	var codeTemplateLibraries = [];
	var channelTags = [];
	var concernedItems = new Map();
	
	// split successful components into groups
	migratedComponents.success.forEach((component) => {
		var targetList = null;
		// determine to which list the name of the component will be added
		switch(component.type) {
		  case 'codeTemplate':
			targetList = codeTemplates;
			break;
		  case 'channel':
			targetList = channels;
			break;
		  case 'codeTemplateLibrary':
			targetList = codeTemplateLibraries;
			break;
		  case 'channelGroup':
			targetList = channelGroups;
			break;
		  case 'channelTag':
			targetList = channelTags;
			break;
		}
		// indicate success
		component.status = 'success';
		if(targetList){
			targetList.push(component.name);
		}
		concernedItems.set(component.name + '_' + component.type, component);
	});
	
	// split failed components into groups
	migratedComponents.failure.forEach((component) => {
		var targetList = null;
		// determine to which list the name of the component will be added
		switch(component.type) {
		  case 'codeTemplate':
			targetList = codeTemplates;
			break;
		  case 'channel':
			targetList = channels;
			break;
		  case 'codeTemplateLibrary':
			targetList = codeTemplateLibraries;
			break;
		  case 'channelGroup':
			targetList = channelGroups;
			break;
		  case 'channelTag':
			targetList = channelTags;
			break;
		}
		// indicate failure
		component.status = 'failure';
		if(targetList){
			targetList.push(component.name);
		}
		concernedItems.set(component.name + '_' + component.type, component);
	});
	
	// split skipped components into groups
	componentsToSkip.forEach((component) => {
		var targetList = null;
		// determine to which list the name of the component will be added
		switch(component.type) {
		  case 'codeTemplate':
			targetList = codeTemplates;
			break;
		  case 'channel':
			targetList = channels;
			break;
		  case 'codeTemplateLibrary':
			targetList = codeTemplateLibraries;
			break;
		  case 'channelGroup':
			targetList = channelGroups;
			break;
		  case 'channelTag':
			targetList = channelTags;
			break;
		}
		// indicate failure
		component.status = 'skipped';
		if(targetList){
			targetList.push(component.name);
		}
		concernedItems.set(component.name + '_' + component.type, component);
	});
	
	// sort all lists in alphabetical order
	channels.sort();
	codeTemplates.sort();
	channelGroups.sort();
	codeTemplateLibraries.sort();
	channelTags.sort();

	// build dialog
	var resultMessage =   "<b><u>Migration Report:</u></b><br/><br/><table>";
	// add code templates
	resultMessage += createReportTableEntries(codeTemplates, concernedItems, 'codeTemplate', 'code template');
	// add code template libraries
	resultMessage += createReportTableEntries(codeTemplateLibraries, concernedItems, 'codeTemplateLibrary', 'code template library');
	// add channels
	resultMessage += createReportTableEntries(channels, concernedItems, 'channel', 'channel');
	// add channel groups
	resultMessage += createReportTableEntries(channelGroups, concernedItems, 'channelGroup', 'channel group');
	// add channel tags
	resultMessage += createReportTableEntries(channelTags, concernedItems, 'channelTag', 'channel tag');
	
	// add a button for closing the migration report
	resultMessage += "</table><br/><button type='button' onClick='finalizeMigration();' id='confirmButton'><img src='/img/ok.png'/></button>";
	// remove migration progress indicator
	showMigrationIndicator(false);
	// display dialog
	var migrationReport = $("#migrationReport");
	$(migrationReport).html(resultMessage);
	
	componentsToSkip = [];
	
	//$(migrationReport).offset({top: 100, left: 100});
	$(migrationReport).css("position","absolute");
	$(migrationReport).css("top", Math.max(0, (($(window).height() - $(migrationReport).outerHeight()) / 2) + $(window).scrollTop()) + "px");
	$(migrationReport).css("left", Math.max(0, (($(window).width() - $(migrationReport).outerWidth()) / 2) + $(window).scrollLeft()) + "px");
	$(migrationReport).css('visibility', 'visible');
}    

/**
   Creates entries for the migration report 
   @param {String[]} componentNames An array with component names
   @param {Map} componentList The map containing the components
   @param {String} componentType The type of the compents for which the migration report should be migrated.
   @param {String} componentDisplayType The text string that should be displayed for the component type in the migration report
   @return {String} table rows, representing the migration report for the provided components
 */
function createReportTableEntries(componentNames, componentList, componentType, componentDisplayType){
	var result = '';
	
	componentNames.forEach((componentName) => {
		var component = componentList.get(componentName + '_' + componentType);
		// determine status color and text
		var color = 'black';
		var status = 'unknown';
		switch(component.status) {
		  case 'success':
			color = 'LimeGreen';
			status = 'success';
			break;
		  case 'failure':
			color = 'Red';
			status = 'failed';
			break;
		  case 'skipped':
			color = 'DarkOrange';
			status = 'skipped';
			break;
		}

		// add a table row to the result
		result += '<tr><td>' + componentDisplayType + '</td><td><b>' + componentName + '</b>:</td><td id="migrationReportDetails" componentId="' + (component.id || '') + '" errorCode"' + (component.errorCode || '') + '" errorMessage"' + (component.errorMessage || '') + '"> <font color="' + color + '">' + status + '</font></td></tr>\n';
		// if there is a list of contained functions (so far just used for code templates)
		if(component.function){
			// bring them into alphabetical order
			component.function.sort();
			// and add them to the report
			for (const functionName of component.function) {
				result += '<tr><td align="right" class="containsFunction">contains function</td><td class="containsFunction"><b>' + functionName + '</b></td><td></td></tr>\n';			
			}
		}
	});
	
	return result;
}

function showMigrationIndicator(displayIt){
	var migrationIndicator = $('#migrationIndicator');

	if(displayIt){
		// position the migration indicator
		$(migrationIndicator).css("position","absolute");
		$(migrationIndicator).css("top", Math.max(0, (($(window).height() - $(migrationIndicator).outerHeight()) / 2) + $(window).scrollTop()) + "px");
		$(migrationIndicator).css("left", Math.max(0, (($(window).width() - $(migrationIndicator).outerWidth()) / 2) + $(window).scrollLeft()) + "px");
		// and display it
		$(migrationIndicator).css('visibility', 'visible');
	} else{
		// and hide the migration indicator
		$(migrationIndicator).css('visibility', 'hidden');
	}
}

/**
 * When the ok button of the migration report dialog is pressed, the main window is updated 
 * and the migration environment is replaced by the main window.
 */
function finalizeMigration(){
	// update tables
	populateComponentTables(true); 

	// hide migration screen
	document.getElementById("collapseContentIcon").style.visibility = "hidden";    
	document.getElementById("preComponentContent").style.visibility = "hidden";
	document.getElementById("contentHeader").style.visibility = "hidden";
	document.getElementById('metaDataSection').style.visibility = "hidden";
	document.getElementById('collapseMetaDataIcon').style.visibility = "hidden";
	var migrationReport = $("#migrationReport");
	$(migrationReport).html('');
	$(migrationReport).css('visibility', 'hidden');
	// and show main window
	$("#migrationMainWindow").css('display','block');
}

/**
 * Compares two versions of a component that are located on different systems
 * @param {String} statusCode The return code of the compare call
 * @param {String} response The response from the server
 */
function compare(statusCode, response){

	currentSourceComponentContent = clone(response.sourceContent + "");
	currentDestinationComponentContent = clone(response.destinationContent + "");
	$("#migrationMainWindow").css('display','none');
	$("#overlay").css('display','block');
	$("#componentVersionConflict").css('display','none');
	$("#mirthVersionConflict").css("display", "none");
	$("#conflictDiv").css('display','block');
	document.getElementById("closeButton").style.visibility = "visible";

	updateDiff(currentSourceComponentContent, currentDestinationComponentContent, 1);
	createConflictMetaDataTable(response.metaData);
}

/**
 * Changes the background color of the selected option of a select box
 * @param {String} selectBox The reference to the select box for which the change should be applied
 */
function changeSelectionColor(selectBox){
	// get the color of the selected option
    var color = $(selectBox).find("option:selected").attr("color");
	// if there was a color configured use it. Otherwise use white (just to be sure)
	$(selectBox).css('background-color', color ? color : '#ffffff'); 
}

/**
 * Changes the background color of the selected option of a select box
 * @param {String} selectBox The reference to the select box for which the change should be applied. If none is provided, it will be applied to all select boxes
 */
function activateSelectionColorChanger(selectBox){
	if(selectBox){
		// adapt the color to the current selection
		changeSelectionColor(selectBox);
		
		// and also at change events
		$(selectBox).off('change').on('change', function() {
			changeSelectionColor(selectBox); 
		});		
	}else{
		// for all select boxes
		$('select').each(function() {
			// adapt the color to the current selection
			changeSelectionColor(this);
			
			// and also at change events
			$(this).off('change').on('change', function() {
				changeSelectionColor(this); 
			});
		});
	}
}

/**
 * Changes the bordercolor of a table
 * @param {String} systemType Either sourceSystem or destSystem.
 */
function changeTableBorderColor(systemType){
	var color = $("#" + systemType).find("option:selected").attr("color");
    if(systemType=="sourceSystem") document.getElementById("tableSource").style.border = "thick solid " + color;
    else document.getElementById("tableDest").style.border = "thick solid " + color;
}

/**
 * Displays the hidden list box of a system
 * @param {String} systemType Either sourceSystem or destSystem
 */
function displayHiddenBoxes(systemType){
    if(!sourceBoxShown && systemType == 'sourceSystem'){     
        this.sourceBoxShown =true;	
		$("#componentTableLeft").css({
			"display": "inline-block",
			"visibility": "visible"
		});

    }
    if(!destBoxShown && systemType == 'destSystem'){   
        this.destBoxShown = true;
		$("#componentTableRight").css({
			"display": "inline-block",
			"visibility": "visible"
		});
    }
}

/**
 * This function updates the headers of either the source or destination side
 * @param {*} systemType Either sourceSystem or destSystem, tells which side to update.
 * @param {*} displayList The json-structure containing the information about the mirth instance
 */
function setSelectionHeaders(systemType, displayList)
{	// get system name
	var system = $('#' + systemType + ' option:selected').attr('id');

	// set the name of group and member elements corresponing to the type
	if($('input[name = "compType"]:checked').val() == 'channelGroup'){
		var groupType = "groups";
		var componentType =  "channels";	
	} else{
		var groupType = "libraries";
		var componentType = "code templates";		
	}
		
	// assgemble the status message
    var displayString = system + ': ' + 
						displayList['Number of groups'] + ' ' + 
						((displayList['Number of groups'] != 1) ? groupType : groupType.substring(0, groupType.length - 1)) + ', ' + 
						displayList['Number of members'] + ' ' + 
						((displayList['Number of members'] != 1) ? componentType : componentType.substring(0, componentType.length - 1)) + ' (Mirth v' + displayList['Mirth version'] + ')' ;

   $('#' + systemType + "Header").text(displayString);
}

/**
 * This function populates a table
 * @param {*} statusCode
 * @param {*} displayList
 * @param {*} parameters
 */
function populateTable(statusCode, displayList, parameters){

		var table = '';
		var isSource = (parameters.systemType == 'sourceSystem');
		// determine the environment color
		var color = $('#' + parameters.systemType + ' option:selected').attr('color');
		// and adjust the border color of the div hosting the table
		$(isSource ? '#tableSource' : '#tableDest').css('border', 'thick solid ' + color);

		// check if a row in this table is currently selected
		if($('#' + (isSource ? 'sourceComponents' : 'destComponents') + ' > tbody > tr.highlight').length){
			// indeed, it is. So remove the migration button as selection is no longer valid
			$("#migrateButton").css('visibility', 'hidden');
			$("#compareButton").css('visibility', 'hidden');
		}

		//empties the table
		$('#' + parameters.systemType + "Items").empty();
		var itemList = displayList['item'];

		// if there are no items, there is nothing to do
		if(itemList == undefined){
			return;
		}

		// now add the items to the table
		for(var index = 0; index < itemList.length; index++){
			// get next item
			var currentItem = itemList[index];
			//checks if description exists and creates row in case it exists
			var description = currentItem['Description'] ? '<img src="/img/info.png" tooltip="'+ currentItem['Description'].replace(/"/g, '&quot;') + '" id="descriptionIcon" class="dontWrap">' : ''; 
			if(currentItem['Issues']){
				// if there is no info icon
				if(!description){
					// add some extra space to vertically align the warning icons (more or less)
					description = '&nbsp;&nbsp;&nbsp;&nbsp;';
				}
				description += '&nbsp;&nbsp;<img src="/img/warning.png" tooltip="';
				
				var issues = currentItem['Issues'];
				var issueInfo = '';
				if(currentItem['Type'] == 'channel'){
					// add missing code template library references errors					
					if(issues['missingReferences']){
						description += 'The following code template library <b>reference'+ ((issues['missingReferences'].length == 1) ? ' is missing' : 's are missing') + '</b>:\n<ul><li>'+ issues['missingReferences'].sort().join('</li><li>').replace(/"/g, '&quot;') + '</ul>\n';
					}
					// add missing function definition errors
					if(issues['unknownFunctions']){
						description += 'For the following reference' + ((issues['unknownFunctions'].length > 1) ? 's' : '') + ' <b>no function definition</b> was found:\n<ul><li>'+ issues['unknownFunctions'].sort().join('</li><li>').replace(/"/g, '&quot;') + '</ul>\n';
					}
				}else{
					// add functions that have multiple definitions errors
					if(issues['multipleDefinitions']){
						description += 'The function has been <b>defined multiple times</b> in the following code template' + ((issues['multipleDefinitions'].length > 1) ? 's' : '') + ':\n<ul><li>'+ issues['multipleDefinitions'].sort().join('</li><li>').replace(/"/g, '&quot;') + '</ul>\n';
					}
				}
				// finalize the description item
				description += issueInfo + '" id="warningIcon"  class="dontWrap">';
			}
			// format the current line depending the item type (group or member)
			if(currentItem['Group']){
				// create a line for a group element
				table += 	'<tr draggable="true" tabindex="0" class="' + ((currentItem['artificial']) ? 'unassigned" rowType="artificial"' : 'group" rowType="group"') + 
							' type="' + currentItem['Type'] + 
							'" id="' + parameters.systemType + currentItem['Id'] + 
							'" itemId="' + currentItem['Id'] + 
							'" name="' + currentItem['Display name'] + 
							'"><td>' + currentItem['Display name'] + 
							' (' + currentItem["Number of members"] +
							')</td><td style="text-align: left;">' + description + 
							'</td><td>' + currentItem['Display date']  + 
							'</td><td class="right">' + currentItem['Version'] + 
							'</td></tr>';
			} else{
				table += 	'<tr draggable="true" tabindex="0" class="list_' + (index % 2) + 
							' customContextMenu" type="' + currentItem['Type'] + 
							'" id="' + parameters.systemType + currentItem['Id'] + 
							'" itemId="' + currentItem['Id'] + 
							'" name="' + (currentItem['Function name'] || currentItem['Display name']) + 
							'" style="color:' + ((currentItem['Is disabled']) ? 'LightSlateGray' : 'black') + 
							';"><td>' + (currentItem['Function name'] || currentItem['Display name']) + 
							'</td><td style="text-align: left;">' + description + 
							'</td><td>' + currentItem['Display date']  + 
							'</td> <td class="right">' + currentItem['Version'] + 
							'</td></tr>';
			}
		}      
		
		$('#' + parameters.systemType + "Items").append(table);
		table = '';

		//do not display checkbox for avoiding to migrate refrenced code templates before anything has been clicked
		if(isSource){
			$("#migrateReferencedCodeTemplatesCheckboxDivLeft").css('display','none');
		}else{
			$("#migrateReferencedCodeTemplatesCheckboxDivRight").css('display','none');
		}
		
		// cache the different layouts for table entries
		cacheHighlightClasses($("#" + parameters.systemType + "Items tr"));
		
		// highlight the column that is hovered
		activateHoverEffect();
		
		// and also the context menu for the components listed in the table
		activateContextMenu();
				
		// activate custom tooltips for the info icons
		activateToolTips();
			

		//If a line in this table is clicked, details about the component should be displayed
		$("#" + parameters.systemType + "Items tr").click(function(event) {
			//checks if the highlighting on another table has to be removed because it was clicked on the other side
			checkSelection(isSource ? "tableDest" : "tableSource");

			// determine if checkbox for also migrating referenced code templates should be displayed
			adjustAvoidReferencedTemplatesOption(isSource);
			//sets the meta data headers corresponding to the selected row such as component type, name and system
			setMetaDataHeaders($(this), parameters.systemType);
			
			// <CRTL>-key was pressed when row was clicked: highlights the selected row
			if(event.ctrlKey){
				// select multiple
				highlight($(this), parameters.systemType, true);
				if($("#"+ (isSource ? "tableSource" : "tableDest") + " tbody tr.highlight").length > 1){
					checkSelection(isSource ? "tableDest" : "tableSource");
				}

				metaDataChecker($(this), parameters.systemType);
				
			} else if(event.shiftKey){
				
				// <SHIFT>-key was pressed when line was clicked
				// select range
				if($(selectedId).index() > $(this).index()){
					while($(selectedId).index() > $(this).index()){
						highlight($(selectedId).prev("tr"), parameters.systemType, true);
					}
				} else{	
					while($(selectedId).index() < $(this).index()){
						selectedId = $(selectedId).next("tr");
						if (!selectedId.length) break;
						highlight(selectedId, parameters.systemType, true);
					}
				}
				
				if($("#"+ (isSource ? "tableSource" : "tableDest") + " tbody tr.highlight").length > 1){
					checkSelection(isSource ? "tableDest" : "tableSource");
				}

				metaDataChecker($(this), parameters.systemType);
				
			} else{
				// normal click
				//highlights just this one clicked row
				highlight($(this), parameters.systemType, false);
				//checks for a correspondancy on the other system
				metaDataChecker($(this), parameters.systemType);
				// get the name of the system that has been set
				var systemName = $('#' + parameters.systemType + ' option:selected').val();
				// also the id of the selected component
				var componentId =$(this).attr('id').replace(parameters.systemType, "");
				// and it's type (well this should somewhen changed to the type attribute)
				var componentType = $(this).attr('type');
				//setCheckBoxes(componentType);   
				var payload = {
									"system": systemName,
									"component": {
										"type": componentType,
										"id": componentId
									}
								}
				//calls the ajax function to retrieve information to fill the metadata-table and the content section
				accessResource("/getComponentDetails", payload, showComponentDetails, {'systemType': parameters.systemType});     
			}
		});
		//dragDropTable(parameters.systemType);
		
		$(isSource ? '#tableSource' : '#tableDest').keydown(function (e) {
			e.preventDefault();
		});
		var table = isSource ? "#sourceComponents" : "#destComponents";
		// Cursor key was pressed:
		$("table tbody.list tr").off("keydown").on("keydown", function(e) {
			switch(e.which)
			{
				//key LEFT pressed
				case 37:
					if($("#sourceComponents tr.correspondance").length > 0){
						$("#sourceComponents tr.correspondance").click().focus();
					}
					break;
				//key UP pressed
				case 38:
					// disable cursor movement at multiselect and when end of table is reached
					if (($(this).length == 1) && ($(this).index() > 0)){
						
						// remove the highlighting from the hover effect if the cursor moves on
						$(this).attr("class", $(this).data($(this).attr("class").includes("hoverHighlight") ? "hoverClasses" : "standardClasses"));		

						// activate the previous row
						selectedId = $(this).prev("tr");
						
						// and make sure the selected item is still visible
						scrollIntoView(selectedId);					
					}
					break;
				//key RIGHT pressed
				case 39:
					if($("#destComponents tr.correspondance").length > 0){
						$("#destComponents tr.correspondance").click().focus();
					}
					break;
				//key DOWN pressed
				case 40:
					// disable cursor movement at multiselect and when end of table is reached
					if (($(this).length == 1) && ($(this).index() <$(this).closest("tbody").children("tr").length - 1)){
						
						// remove the highlighting from the hover effect if the cursor moves on
						$(this).attr("class", $(this).data($(this).attr("class").includes("hoverHighlight") ? "hoverClasses" : "standardClasses"));	

						// activate the next row
						selectedId = $(this).next("tr");
						
						// activate the next row
						selectedId = $(this).next("tr");
						// and make sure the selected item is still visible
						scrollIntoView(selectedId);					
					}

					break;	
			}
		});
		

		setSelectionHeaders(parameters.systemType, displayList);
		// show the button bar if not already visible
		if($("#flexBoxButtons").css('visibility') == 'hidden'){
			$("#flexBoxButtons").css('visibility', 'visible');
		}
}

/**
 * caches normal, hover & highlighting layout for every item in the component table
 * @param {*} tableBody - The table body containing the components
 */
function cacheHighlightClasses(tableBody){
	if(!tableBody || !tableBody.length){
		return;
	}
	
	tableBody.each(function(){
		// standardlayout of the component
		if ($(this).data("standardClasses") === undefined) {
			$(this).data("standardClasses", $(this).attr("class"));
		}
		// layout that is used when a component is clicked
		if ($(this).data("highlightClasses") === undefined) {
			$(this).data("highlightClasses", !$(this).attr('rowType') ? "highlight" : "highlight highlightGroup");
		}
		// layout that is used when the component is hovered
		if ($(this).data("hoverClasses") === undefined) {
			$(this).data("hoverClasses", !$(this).attr('rowType') ? "hover" : "hover hoverGroup");
		}
		// layout that is used when a highlighted component is hovered
		if ($(this).data("hoverHighlightClasses") === undefined) {
			$(this).data("hoverHighlightClasses", !$(this).attr('rowType') ? "hoverHighlight" : "hoverHighlight hoverHighlightGroup");
		}
		// layout that is used if the component is marked to correspond to a component of the other system
		if ($(this).data("correspondanceClasses") === undefined) {
			$(this).data("correspondanceClasses", !$(this).attr('rowType') ? "correspondance" : "correspondance correspondanceGroup");
		}
		// layout that is used if the component that marked to correspond to a component of the other system is hovered
		if ($(this).data("hoverCorrespondanceClasses") === undefined) {
			$(this).data("hoverCorrespondanceClasses", !$(this).attr('rowType') ? "hoverCorrespondance" : "hoverCorrespondance hoverCorrespondanceGroup");
		}
    });
}

/**
 * Displays or hides the option for avoiding to migrate refrenced code templates, depending on component type and selected table
 * @param {*} isLeftTable - Indicates if the function was called from the left or right table
 * @param {*} isLeftTable - Indicates if the function was called from the left or right table
 */
function adjustAvoidReferencedTemplatesOption(isLeftTable){

	// determine the type of the components that are displayed
	var isChannel = $('input[name = "compType"]:checked').val().startsWith('channel');
	// in case of channels
	if(isChannel){
		// only display the option at the mirth instance from which should be migrated
		$("#migrateReferencedCodeTemplatesCheckboxDivLeft").css('display', isLeftTable ? 'block' : 'none');
		$("#migrateReferencedCodeTemplatesCheckboxDivRight").css('display', isLeftTable ? 'none' : 'block');		
	} else{
		// in case of code templates the option should not (yet) be available
		$("#migrateReferencedCodeTemplatesCheckboxDivLeft").css('display','none');
		$("#migrateReferencedCodeTemplatesCheckboxDivRight").css('display','none');
	}
}

/**
 * Synchronizes the two checkboxes that indicate if referenced code templates should also be migrated
 * @param {*} master - The reference to the checkbox of which the status should be taken
 * @param {*} slave - The id of the checkbox to which the status should be copied
 */
function syncCheckbox(master, slave){
	// copy the state of the master to the slave
	$('#' + slave).prop('checked', $(master).is(':checked'));
}

/**
 * Shows the meta data as well as the source code of a component
 * @param {*} statusCode - The return code of the ajax request
 * @param {*} componentDetails - The ajax response message containing the component information
 * @param {*} parameters - Additional parameters needed by the component. In this case the information about the diplay box that should be updated w/ the component details
 */
function showComponentDetails(statusCode, componentDetails, parameters){
					
	if(parameters.systemType == "sourceSystem"){
		currentSourceComponentContent = componentDetails.content;
	}
	else{
		currentDestinationComponentContent = componentDetails.content;
	}
	populateMetaDataSection(componentDetails);  
	populateContentSection(componentDetails);
}

/**
 * Defines if the checkboxes should be displayed or not.
 * @param {*} componentType the type of the selected component.
 */
function setCheckBoxes(componentType){
    if(componentType=="channelGroup" || componentType=="channel"){
        document.getElementById("sourceCheckBox").style.display = "block";
        document.getElementById("destCheckBox").style.display= "block";
    }
    else{
        document.getElementById("sourceCheckBox").style.display= "none";
        document.getElementById("destCheckBox").style.display= "none";
    }
}

/**
 * Assures that the selected table row remains visible
 * 
 * @param tableRow the row that should remain visible
 */
function scrollIntoView(tableRow) {

	// div containing the table
	var container = tableRow.closest("div");
	// position of visible upper table border
	var containerTop = container.scrollTop();
	// position of visible lower table border
	var containerBottom = containerTop + container.height();
	// position of of currently selected table row upper border
	var rowTop = tableRow.position().top;
	// position of of currently selected table row lower border	
	var rowBottom = rowTop + tableRow.outerHeight();

	// leaving the upper border
	if (containerTop > rowTop) {
		// Scroll up
		container.scrollTop(containerTop - (containerTop - rowTop));
		
	// leaving the lower border
	} else if (containerBottom < rowBottom) {
		// Scroll down
		container.scrollTop(containerTop + (rowBottom - containerBottom));
	}
	tableRow.click();
}


/**
 * This function is called every time a user clicks, alas highlights a row and checks if there are highlighted elements on the other table.
 * @param {*} correspondingContainer 

 */
function checkSelection(correspondingContainer){
    var $selectedRows = $("#"+ correspondingContainer + " tbody tr.highlight");
    if($selectedRows.length > 0){
		$selectedRows.each(function(i, row){
			removeHighlighting(this);
		});
    }
}
/**
 * This function enables the drag and drop functionality for each table.
 * @param {*} systemType  Indicates which table shall be drag and drop enabled.
 */
function dragDropTable(systemType) {
    var $tabs=$('#tableDest')
    $( "tbody.connectedSortable" )
        .sortable({
            connectWith: ".connectedSortable",
            items: "> tr:not(:first)",
            appendTo: $tabs,
            helper:"clone",
            zIndex: 999990
        })
        .disableSelection()
    ;

    var $tab_items = $( ".nav-tabs > li", $tabs ).droppable({
      accept: ".connectedSortable tr",
      hoverClass: "ui-state-hover",
      drop: function( event, ui ) {
      }
    });
}
/**
 * Assembles the header of the metadata table of a component
 * @param {*} $row The row that was clicked.
 * @param {*} systemType The systemtype on which a row was clicked so either sourceSystem or destSystem
 */
function setMetaDataHeaders($row, systemType){
    var componentType = $row.attr('type');
    switch(componentType){
        case "channel":
            componentType = "channel";
            break;
        case "channelGroup":
            componentType = "channel group";
            break;
        case "codeTemplateLibrary":
            componentType = "code template library";
            break;
        case "codeTemplate":
            componentType = "code template";
            break;
    }
    var componentName = $row.find("td:nth-child(1)").html().trim();
	var system = $('#' + systemType + ' option:selected').attr('id');
    var systemTypeNice = (systemType=="sourceSystem") ? "source system" : "destination system";
    document.getElementById("selectedComponent").innerHTML = " Details of " + componentType + " \""  + componentName 
                                                                + "\" on " + system + " (" + systemTypeNice + ")";
}

/**
 * Removes highlighting and reconstructs the look of a table row 
 * 
 * @param tableRow the row form which the highlighting should be removed
 */
function removeHighlighting(tableRow){
 
	if(!(tableRow instanceof jQuery)){
		tableRow = $(tableRow);
	}
	
	if(tableRow.attr("class").includes("hoverHighlight") || tableRow.attr("class").includes("hover")){
		// the row was hovered, make sure the hovering appearance remains (albeit w/o highlighting)
		tableRow.attr("class", tableRow.data("hoverClasses"));	
	} else{
		// the current row was not hovered set it back to standard appearance
		tableRow.attr("class", tableRow.data("standardClasses"));
	}	
}

/**
 * Highlights the chosen row and triggers the display of the corresponding message data
 * 
 * @param row the row that should be highlighted
 * @param systemType Indicates on which table the row is
 * @param multiSelectKeysPressed Indicates if the user pressed STRG/COMMAND while clicking, which allows to select more rows.
 */
function highlight(row, systemType, multiSelectKeysPressed) {

	var leftToRight = (systemType == "sourceSystem");
    var tableID = leftToRight ? "sourceComponents" : "destComponents";
    var tbody = $("#" + tableID + " tbody");
	// all items that are already selected 
    var selected = tbody.children('tr.highlight, tr.hoverHighlight');
	
	var alreadyHighlighted = row.attr("class").includes("hoverHighlight") || row.attr("class").includes("highlight");

	// items have been selected before but multi-row select keys are currently not pressed.
    if(!multiSelectKeysPressed){
		// deselect all currently selected items besides if the currently selected item was pressed again
		$(selected).each(function(){
			removeHighlighting(this);
		});
		// if multiple items were selected before and the current one was amongst them, the current one should remain selected but not the others
		alreadyHighlighted = false;
    }

	// If both boxes are filled
	if(sourceBoxShown && destBoxShown){
		// if the selected item has been deselected
		if(row.attr("class").includes("hoverHighlight")){
			// hide the migrate and the compare button
			$("#migrateButton").css('visibility', 'hidden');
		} else{
			// show the migration button for left to right respectively right to left, depending on whre the highlighted components are located
			$("#migrationDirectionIcon").attr("src", "/img/" + (leftToRight ? "arrowRight.png" : "arrowLeft.png"));
			// provide the possibility to migrate the selected component(s)
			$("#migrateButton").css('visibility', 'visible');		
		}
	}
	
	// row was already selected
    if(alreadyHighlighted && !multiSelectKeysPressed){
		// thus deselect by removing highlighting
        removeHighlighting(row);
		selectedId = "";
    } else{
		if(row.attr("class").includes("hoverHighlight") || row.attr("class").includes("hover")){
			// the row was hovered, make sure the hovering appearance remains (albeit w/o highlighting)
			row.attr("class", row.data("hoverHighlightClasses"));
		} else{
			// the current row was not hovered set it back to standard appearance
			row.attr("class", row.data("highlightClasses"));
		}
        selectedId = "#" + row.attr('id');
		row.focus();
    }
}

/**
 * checks for a corresponding row on the other table.
 * @param {*} selectedRow The row that was clicked.
 * @param {*} systemType The systemtype on which a row was clicked so either sourceSystem or destSystem
 */
function metaDataChecker(selectedRow, systemType){
	var sourceTbody;
	var destinationTbody = null;
	var correspondingRow;
	var correspondingComponentId = null;
	
	if(systemType == "sourceSystem"){
		// if the right table is actually shown
		if($('#componentTableRight').css('visibility') == 'visible'){
			sourceTbody = $("#sourceComponents tbody");		
			destinationTbody = $("#destComponents tbody");
			// if a row in the left table was highlighted
			if(selectedRow){
				// try to find the corresponding row in the right table
				correspondingRow = $('#tableDest tr[name="' + selectedRow.attr('name') + '"]');
			}			
		}
	}else{
		// if the left table is actually shown
		if($('#componentTableLeft').css('visibility') == 'visible'){
			sourceTbody = $("#destComponents tbody");		
			destinationTbody = $("#sourceComponents tbody");
			// if a row in the right table was highlighted
			if(selectedRow){
				// try to find the corresponding row in the left table
				correspondingRow = $('#tableSource tr[name="' + selectedRow.attr('name') + '"]');
			}
		}
	}
	
	// if no system type was provided
	if(!systemType){
		// address both tables anyway for being able to disable highlighting
		if($('#componentTableLeft').css('visibility') == 'visible'){
			sourceTbody = $("#sourceComponents tbody");	
		}		
		if($('#componentTableRight').css('visibility') == 'visible'){
			destinationTbody = $("#destComponents tbody");
		}
	}

	// if a table is visible
	if(sourceTbody){
		// remove any highlighted correspondance elements that might exist
		var selected = sourceTbody.children('tr.correspondance');
		$(selected).each(function(){
			removeHighlighting(this);
		});
	}
	
	// if the other table is shown
	if(destinationTbody){
		// remove any highlighted elements that might exist
		var selected = destinationTbody.children('tr.correspondance');
		$(selected).each(function(){
			removeHighlighting(this);
		});			
	}

	// if currently a) no row is highlighted, b) no other table shown, or c) multiselect is active
	if(!systemType || !selectedRow || !destinationTbody || !sourceTbody || (sourceTbody.children('tr.highlight').length > 1)){
		// do not show the compare button
		$('#compareButton').css('visibility', 'hidden');
		
		$(correspondingRow).focus();
		// no need to search for a corrsponding item
		return;
	}

	// if a corresponding component was found at the other Mirth instance
    if($(correspondingRow).length){
		// highlight it
		$(correspondingRow).attr("class", $(correspondingRow).data("correspondanceClasses"));
		// make it visible (scroll it into view)
		var container = $(correspondingRow).closest("div");
		if(container.length){
			// + scrollbar vertical position
			container.animate({scrollTop: $(correspondingRow).offset().top - container.offset().top + container.scrollTop()}, 1);
		}
		//and  also remember it - ODO: However not sure if still needed - RECHECK
		selectedCorrespondanceItemId = correspondingComponentId;
		// do not show the compare button for grouping components
		$('#compareButton').css('visibility', !selectedRow.attr('rowType') ? 'visible' : 'hidden');
	}
}

/**
 * Sets the metadataSection visible and creates the table with the components metadata.
 * @param {*} component The component in JSONformat to be printed in detail in the metadatatable
 * @param {*} componentType The type of the component so either channels, codeTemplates, channelgroups or codeTemplateLibraries
 */
function populateMetaDataSection(component, componentType){
    metaDataTableShown=true;

	$('#metaDataSection').css({'display': 'block', 'visibility': 'visible'});
	$('#collapseMetaDataIcon').css('visibility', 'visible');

    createMetaDataTable(component, componentType);
}

/**
 * Creates the table of the metadatasection
 *@param {*} component The component in JSONformat to be printed in detail in the metadatatable
 * @param {*} componentType The type of the component so either channels, codeTemplates, channelgroups or codeTemplateLibraries
 */
function createMetaDataTable(component, componentType) {
 
   var content = [];
	// add table tag
	content.push('<table id="metaDataTable" cellspacing="0">');
	// add the table header
	content.push('<tr><th>Attribute</th><th>Value</th></tr>');
	var key;
	var value = '';
	// the fields and there order as they should be shown
	var keyList = ['Function Name', 'Name', 'Type', 'Description', 'Outbound Interfaces', 'Inbound Interfaces', 'Changes', 'Parameters', 'Return value', 'Version', 
	'Display date', 'Id', 'Number of channels', 'Number of invalid references', 'Number of references', 'Referenced by channels', 'Channel status', 
	'Referenced Libraries', 'Referenced by functions', 'Uses functions'];
	// now add a line for each meta data
	for(var index in keyList){
		// get the key
		key = keyList[index];
		// get the value for the key
		value = component[key];

		if(!value){
			// don't show attributes without value
			continue;
		}
		
		// values that are of type array
		if(value instanceof Array){
			var structured = '<ol>';
			// will be structured as a list
			for(var elementIndex in value){
				structured += '<li>'+value[elementIndex] + '</li>';
			}
			value = structured + '</ol>';
		}
	
		// some specific attributes will we highlighted in red
		if('|Number of invalid references|'.indexOf('|' + key + '|') > -1){
			key = '<font color="red">' + key + '</font>';
			value = '<font color="red"><b>' + value + '</b></font>';			
		}
	
		// add a hacky fix to clarify the difference between function name and code template name
		if((component.Type == 'Code Template') && (key == 'Name')){
			key = 'Template Name';
		}
		
		// change display date to a more speaking key
		if(key == 'Display date'){
			key = 'Last Change';
		}
		
		// add the attbribute as a table row
		content.push('<tr><td align="left" valign="top"><b>' +  key + 
				':</b> </td><td id="description">' + 
				value + 
				'</td></tr>');
	}
	// assemble the code for creating the meta data table
	content.push('</table>');
	
	// add the table to the div
	$('#metaDataTable').html(content.join(''));
	
	// activate copy to clipboard for the attribute values
	$('#metaDataTable tr td:nth-child(2)').click(function() { 
		var text = $(this).clone().find('br, li').append('\r\n').end().text();
		var temp = $('<textarea>').appendTo('body').val(text).select();
		document.execCommand('copy');
		temp.remove();
		// provide user feedback about the action by flashing the copied element
		$(this).addClass("flashOnCopyContent");

		var uncheck = $(this);
		setTimeout(function() {
			// after a delay of 200ms switch back to the original background color
			uncheck.removeClass("flashOnCopyContent");
		}, 300);
	});
}

function htmlToElements(html) {
    var template = document.createElement('template');
    template.innerHTML = html;
    return template.content.childNodes;
}

/**
 * Creates the table of the metadatasection
 * @param {*} metaData The metadata elements of the conflicting components
 */
function createConflictMetaDataTable(metaData) {
    var div = document.getElementById('conflictMetaDataTable');
    div.removeChild(div.childNodes[0]);
    var table = document.createElement('table');
    // Insert New Row for table at index '0'.
    var row1 = table.insertRow(0);
	row1.style.backgroundColor = '#99ccff';
    // Insert New Column for Row1 at index '0'.
    var row1col1 = row1.insertCell(0);
    
	row1col1.style.fontWeight = "bold";
    row1col1.innerHTML = 'Attribute';
	
    // Insert New Column for Row1 at index '1'.
    var row1col2 = row1.insertCell(1);
    row1col2.innerHTML = '<b>' + getSourceSystemName() + '</b> (Source)';	
	
    var row1col3 = row1.insertCell(2);
	row1col3.innerHTML = '<b>' + getDestinationSystemName() + '</b> (Destination)';

    var columnValues=[];
    var columnSourceValues = [];
    var columnDestValues = [];
    var startRowIndex = 1;
    //loop through all attributes of the sourceComponent.
    
	if(metaData.name){
		addConflictTableRow('name', metaData.name, table, startRowIndex++);
	}
	if(metaData.id){
		addConflictTableRow('id', metaData.id, table, startRowIndex++);
	}
	if(metaData.description){
		addConflictTableRow('description', metaData.description, table, startRowIndex++);
	}
	if(metaData.parameters){
		addConflictTableRow('parameters', metaData.parameters, table, startRowIndex++);
	}
	if(metaData.returnValue){
		addConflictTableRow('returnValue', metaData.returnValue, table, startRowIndex++);
	}	
	if(metaData.version){
		addConflictTableRow('version', metaData.version, table, startRowIndex++);
	}
	if(metaData.lastModified){
		addConflictTableRow('lastModified', metaData.lastModified, table, startRowIndex++);
	}
	
    //apply css 
    table.setAttribute('class', 'metaDataTable');
    // Append Table into div.
    div.appendChild(table);
}

function addConflictTableRow(key, value, table, currentRow){

	var row = table.insertRow(currentRow);
	var rowcol1 = row.insertCell(0);
	rowcol1.innerHTML = key;
	var rowcol2 = row.insertCell(1);

	var rowcol3 = row.insertCell(2);
	// set the parameter value of the two systems
	rowcol2.innerHTML = value['source'];
	rowcol3.innerHTML = value['destination'];
	

	if( rowcol2.innerHTML != rowcol3.innerHTML){
		rowcol3.style.backgroundColor="OrangeRed";
		rowcol3.style.fontWeight = "bold";
	}
}


/**
 * Populates the content section with either the xml representation of a channel or the code section of a code template.
 * In case a channelgroup or a codeTemplateLibrary row was clicked the contentsection will be hidden
 * @param {*} component The component in JSONformat to be printed in detail in the metadatatable.
 */
function populateContentSection(component){

    if(component.Type=="Channel"){
		$('#preComponentContent').css({'display': 'block', 'visibility': 'visible'});
		$("#contentHeader").css("visibility", "visible");
		$("#collapseContentIcon").css("visibility", "visible");
		
		$("#preComponentContent").attr("class", "xml");
		$("#componentCode").html(component.content.replace(/</g, "&lt;").replace(/>/g, "&gt;"));
    } else if(component.Type=="Code Template"){    
		$('#preComponentContent').css({'display': 'block', 'visibility': 'visible'});
		$("#contentHeader").css("visibility", "visible");
		$("#collapseContentIcon").css("visibility", "visible");

		$("#preComponentContent").attr("class", "javascript");
        //escape html tags 
       // var regexp = /\/\*[\s\S]*?\*\//gm;
       // component.content = unquoteXml(component.content);
        $("#componentCode").html(component.content.replace(/</g, "&lt;").replace(/>/g, "&gt;"));
        hljs.configure({tabReplace: '  '});
    } else{
		$('#preComponentContent').css({'display': 'none', 'visibility': 'hidden'});
		$("#contentHeader").css("visibility", "hidden");
		$("#collapseContentIcon").css("visibility", "hidden");
		
        return;
    }
	
    $('pre code').each(function(index, block) {
		// activate code highlighting for source code section
        hljs.highlightBlock(block);
    });

}

/**
 * This function is the onclick function for clicking the migrate button.
 * It loops over all selected elements and parses them into an array of ids and component types.
 * afterwards it performs the ajax request for checking conflicts.
 */
function checkSelectedComponents(){
	// reset the lists of components that should be migrated & skipped (global vars)
	componentToMigrate = new Map();
	componentsToSkip = [];

	
	// local variables
    var sourceSystem = getSourceSystemId();
    var destSystem = getDestinationSystemId();

	
    var counter = 0;

	// add all highlighted element to the list of elements that should be migrated
    $('table tr.highlight').each(function(){
		// get the name, 
		var componentName = getComponentName($(this));
		// the id, 
        var componentId = getComponentId($(this));
		// and also the type of the selected component
        var componentType = getComponentType($(this));
		
		// If the component has not yet been added to the migration list
        if(!componentToMigrate.has(componentId)){
						
			// if it is a group component, add the contained leaf components
            if("|channelGroup|codeTemplateLibrary|".indexOf('|' + componentType + '|') + 1){	
		
				// get the first child element (if any)
				var currentRow = $(this).next('tr');
				// and try to determine it's type
				componentType = currentRow ? getComponentType($(currentRow)) : null;
				// as long as the next element is a leaf element (and not another group)
				while('channel|codeTemplate'.indexOf(componentType) + 1){
					// determine it's id
					componentId = getComponentId($(currentRow));
					// and name
					componentName =  getComponentName($(currentRow));
					// and check if it is already in the list
					if(!componentToMigrate.has(componentId)){
						// nope, it isn't ==> add it 
						componentToMigrate.set(componentId, {'name': componentName, 'id': componentId, 'type': componentType});
					}
					// shift to the next element (if any)
					currentRow = $(currentRow).next('tr');
					componentType = currentRow ? getComponentType($(currentRow)) : null;
				}
			} else{
				
				// in case of a leaf component (channel or code template) add its attributes to the migration list
				componentToMigrate.set(componentId, {'name': componentName, 'id': componentId, 'type': componentType});
			}
        }
    });

	// if components have been selected for migration
    if(componentToMigrate.size){
		// create the payload
		var payload = {
			'sourceSystem': getSourceSystemName(),
			'destinationSystem': getDestinationSystemName(),
			'component': [...componentToMigrate.values()]
		};

		// if referenced code templates should be migrated w/ the channels
		if(payload.component[0].type.includes('channel') && $('#migrateReferencedCodeTemplatesCheckboxLeft').prop('checked')){
			// obtain the referenced code templates before checking the components for possible migration conflicts
			accessResource('/getReferencedCodeTemplates', payload, addReferencedCodeTemplates, payload);			
		}else{
			// validate for conflicts and migrate
			accessResource('/getConflictingComponents', payload, handleConflictingComponents);
		}
    } else{
        alert("Please select something to migrate");
    } 
}

/**
 * If there are code templates referenced by channels that should be migrated, they will be added to the list of components that should be migrated
 * @param {*} statusCode The return code of the AJAX call
 * @param {*} referencedCodeTemplates A list of code templates that are referenced by the channels that should be migrated
 * @param {*} payload A list of code templates that are referenced by the channels that should be migrated
 */
function addReferencedCodeTemplates(statusCode, referencedCodeTemplates, payload){
	// if there are also referenced code template that should be migrated
	if(referencedCodeTemplates){
		// add them to the migration list
		referencedCodeTemplates.forEach(codeTemplate => {
			// add the code template to the list of components that should be migrated
			componentToMigrate.set(codeTemplate.id, codeTemplate);
		});
		// add the altered component list to the payload
		payload.component = [...componentToMigrate.values()];
	}

	// validate for conflicts and migrate
	accessResource('/getConflictingComponents', payload, handleConflictingComponents);
}

/**
 * Indicates if action should be dome from the left to the right table or the other way round
 *	@return {boolean} true, if the direction is from left to right, false otherwise 
 */
function isLeftToRight(){
	 return $('#sourceComponents tr.highlight').attr('id') ? true : false;
}

/**
 * Provides the identificator of the source system (system identifier or ip)
 *	@return The system identifier of the source system (server name, domain or ip)
 */
function getSourceSystemId(){
	return $(isLeftToRight() ? '#sourceSystem' : '#destSystem').find(":selected").attr("id");
}

/**
 * Provides the identificator of the destination system
 *	@return The system identifier of the destination system (server name, domain or ip)
 */
function getDestinationSystemId(){
	return $(isLeftToRight() ? '#destSystem' : '#sourceSystem').find(":selected").attr("id");
}

/**
 * Provides the display name of the source system
 *	@return The system display name of the source system 
 */
function getSourceSystemName(){
	return $(isLeftToRight() ? '#sourceSystem' : '#destSystem').find(":selected").text();
}

/**
 * Provides the display name of the destination system
 *	@return The system display name of the destination system 
 */
function getDestinationSystemName(){
	return $(isLeftToRight() ? '#destSystem' : '#sourceSystem').find(":selected").text();
}

/**
 * Provides the name of the component table that corresponds to the provided system type
 *	@param {String} systemType - either <b>sourceSystem</b> for the system at the left hand side or <b>destSystem</b> for the system at the right hand side
 *	@return The name of the component table 
 */
function getComponentTableName(systemType){
	return (systemType == 'sourceSystem') ? 'sourceComponents' : 'destComponents';
}

/**
 * Provides the display name of the component
 *	@param {Object} component -The component of which the name is needed
	@return {String} The name of the component
 */
function getComponentName(component){
	return $('td:first', component).text().trim();
}

/**
 * Provides the type of the component
 *	@param {Object} component -The component of which the type is needed
	@return {String} The type of the component (codeTemplate, channel, channelGroup, or codeTemplateLibrary)
 */
function getComponentType(component){
	return component.attr('type');
}

/**
 * Provides the id of the component
 *	@param {Object} component -The component of which the id is needed
	@return {String} The id of the component
 */
function getComponentId(component){
	return $(component).attr('id').replace("sourceSystem", "").replace("destSystem", "").replace(/\_\d+$/m, '');
}

/**
 * This function is supposed to display the comparison of the 2 components.
 *	@param {String} targetSystem - The name of the system on which the target component resides. If none is provided, the component on the target system corresponding to the marked component is used
 *	@param {String} targetComponent - The id of the target component. If none is provided, the component on the target system corresponding to the marked component is used
 */
function compareComponent(targetSystem, targetComponent){
	// at first, obtain a reference to the two involved components
    var sourceComponent = $('#' + (isLeftToRight() ? 'sourceComponents' : 'destComponents') + ' tr.highlight');
//    var destinationComponent = $('#' + (isLeftToRight() ? 'destComponents' : 'sourceComponents') + ' tr.highlight');
	
	// get the type of the component
    var componentType = getComponentType(sourceComponent);

	// Assemble the info about the component of which different versions should be compared
	var customizedHeadingData = ' ' + ((componentType != 'codeTemplate') ? componentType : 'code template') + ' "' + 
								$('td:first', sourceComponent).text().trim() + '":';
	// and adapt the headings of the metadata and content sections
	$("#headingCompareMetadata").html('&nbsp;Metadata of' + customizedHeadingData);
	$("#headingCompareContent").html('&nbsp;Content of' + customizedHeadingData);
	$("#conflictCompareBeautifier").css('height', '100%');
	
	var payload = {
					"sourceSystem": getSourceSystemName(),
					"destinationSystem": targetComponent && targetSystem || getDestinationSystemName(),
					"component": 
						{
							"id": getComponentId(sourceComponent),
							"type": componentType
						}
				}
	// if a specific component was chosen via the context menu
	if(targetSystem && targetComponent){
		//  use it for comparison with the selected component instead of the standard behavior (which is to compare to a component w/ the same name in the other system)
		payload.component.targetId = targetComponent;
	}

	// compare the component versions on the different systems
	accessResource('/compareComponent', payload, compare);
}    
  
/**
 * This function toggles the Content section and changes the images to either arrow up or arrow down as expand or collapse icons.
 */
function toggleContent()
{
    $("#preComponentContent").toggle();
    //$("#preComponentContent").slideToggle();
    if($("#preComponentContent").css('display') == 'none'){
         $('#collapseContentIcon').attr('src','/img/collapse_up.png');
    } else{
        $('#collapseContentIcon').attr('src','/img/expand_down.png');
    }
}

/**
 * This function toggles the metaData section and changes the images to either arrow up or arrow down as expand or collapse icons.
 */
function toggleMetaData(){
    $("#metaDataTable").slideToggle("slow");
    if(metaDataTableShown){
        $('#collapseMetaDataIcon').attr('src','/img/collapse_up.png');
        metaDataTableShown = false;
    }
    else{
        $('#collapseMetaDataIcon').attr('src','/img/expand_down.png');
        metaDataTableShown = true;
    }
}
/**
 * This function resets all migration lists, displays the regular index screen and disables the conflict panels.
 */
function cancelMigration()
{
    $('#sameForAllCheckbox').prop('checked', false);
	$("#overlay").css("display", "none");
	$("#conflictDiv").css("display", "none");
	
    componentToMigrate = new Map();
    componentWithConflict = [];
	
    $('#sameForAllCheckbox').prop('checked', false);
	$("#migrationMainWindow").css('display','block');
	$("#" + (isLeftToRight() ? "source" : "dest") + "Components tr.highlight").focus();
}
/**
 * This function can be invoked when the user is in the process of solving conflicts. By clicking "Migrate" the conflicting 
 * component will be removed from the list of conflicting components. If the checkbox "Do the same for all migration conflicts" is selected, all remaining conflicting components will be 
 * removed from the conflict list and the actual migration process will be initiated
 */
function migrateAnyway(){
	// if the "Do the same for all migration conflicts" checkbox is activated
    if($('#sameForAllCheckbox').is(':checked')){
		// resolve all conflicts
        componentWithConflict = [];
    } else{
		// resolve the current conflict by removing it
        componentWithConflict.splice(0,1);
    }

	$("#conflictDiv").css("display", "block");
	
	// go on w/ the next conflict
    handleConflictingComponents();
	
    $('#sameForAllCheckbox').prop('checked', false);
}

/**
 * This in the onclick function or the migration button on the <b>Mirth version</b> conflict panel.
 * Once the user presses the button "migrate anyways", the Mirth Migrator trys to migrate all conflicting id's automatically to the destination systems
 * mirth version.
 */
function convertAll(){
	// resolve (well, remove) the first conflict from the list 
    componentWithConflict.splice(0,1);

	// go on with the remaining, component-specific conflicts
    handleConflictingComponents();
	
    $('#sameForAllCheckbox').prop('checked', false);
}

/**
 * This function either skips a component in the process of migration, or skips all remaining conflicting migration components, 
 * based on whether the checkbox "Do the same for all" was selected or not.
 */
function skipComponent(){
	// if the "Do the same for all migration conflicts" checkbox is activated
    if($('#sameForAllCheckbox').is(':checked')){

		// cancel the migration for all components
		componentWithConflict.forEach((component) => {
			// indicate that this component has been skipped
			componentsToSkip.push({'name': component.name, 'id': component.id, 'type': component.type, 'status': 'skipped'});
			// remove the compenent from the migration list
            componentToMigrate.delete(component.id);
		});
		
		// empty the list of conflicting components
		componentWithConflict = [];
    } else{
		
		// get the current conflicting component
		var component = componentWithConflict[0];
		// indicate that this component has been skipped
		componentsToSkip.push({'name': component.name, 'id': component.id, 'type': component.type, 'status': 'skipped'});
		// remove the compenent from the migration list
		componentToMigrate.delete(component.id);
		// and finally remove the component from the conflict list
		componentWithConflict.splice(0, 1);
    }
	
	// go on with the next conflict
    handleConflictingComponents();
}

/**
 * This function checks if there are conflicting components, and if so handles the conflict positioned at index 0 of the conflict list.
 * In case no conflicts are left, it invokes the function migrateComponents
 */
function handleConflictingComponents(statusCode, response){ 

    var sourceSystem = getSourceSystemId();
    var destinationSystem = getDestinationSystemId();
	
	// if there are conflicts indicated by the server
	if(response){
		// create a list of conflicts
		componentWithConflict = response.component;
	}

	// if there are no more migration conflicts
    if(componentWithConflict.length == 0){
		// Nope - so the conflict panel isn't needed
		$("#overlay").css("display", "none");
		$("#conflictDiv").css("display", "none");
		
		// if there are any components left for migration 
        if(componentToMigrate.size){
			// define the payload
			var payload = {
				'sourceSystem': getSourceSystemName(),
				'destinationSystem': getDestinationSystemName(),
				'component': [...componentToMigrate.values()]
			};
			// Let's dance!
			showMigrationIndicator(true);
			// & migrate them
			accessResource('/migrateComponents', payload, migrateComponents);
        } else{
			// there is nothing left to migrate. Display the standard view
			populateComponentTables(); 
			$('#collapseContentIcon').css('visibility', 'hidden');
			$('#preComponentContent').css('visibility', 'hidden');
			$('#contentHeader').css('visibility', 'hidden');
			$('#metaDataSection').css('visibility', 'hidden');
			$('#collapseMetaDataIcon').css('visibility', 'hidden');
			$("#migrationMainWindow").css('display','block');
		}
    } else{
		// handle next migration conflict
		var payload = {
			'sourceSystem': getSourceSystemName(),
			'destinationSystem': getDestinationSystemName(),
			'component': componentWithConflict[0]
		};
		// inform the user about the conflict details and let him decide how the conflict should be solved
		accessResource('/getConflicts', payload, handleMigrationConflict);
    }
}

/**
 * This function gets called once the ajax call for a specific conflict returns.
 * In case the component ID equals 00000000-0000-0000-0000-000000000000 a mirth version conflict is implicated.
 * A Mirth version conflict will always be positioned at spot [0] in the conflicts array, and will therefore 
 * always be handled first.
 * In case of this conflict another div is loaded onto the overlay.
 * This process can now be continued by clicking "Migrate Anyways" and following conflicts will be handled, or if no conflicts 
 * occured for the selected components, they will automatically migrated, including a mirth version conversion.
 * In case no mirth version conflict occured, the panel will automatically filled, by handling all conflicts.
 * 
 * @param {Object} response A JSONObject that contains information about the the id of the current conflicting component,
 * its name and type, the corresponding conflicts, as well as the source code of the two components.
 */
function handleMigrationConflict(statusCode, response){
    var conflicts = response.conflicts;
    var numberOfConflicts = response.numberOfConflicts;

	// check for a version conflict
    if(response.id == "00000000-0000-0000-0000-000000000000"){
		// get the mirth verion of migration source w/o build-number
        let sourceMirthVersion = conflicts[0].sourceMirthVersion;
		// get the mirth verion of migration destination w/o build-number
        let destMirthVersion =  conflicts[0].destinationMirthVersion;

        sourceMirthVersion = "Source: " + sourceMirthVersion;
        destMirthVersion =  "Destination: " + destMirthVersion;
        
		// display source and destination versions in the migration conflict dialog
        $("#sourceMirthVersion").html(sourceMirthVersion);
		$("#destMirthVersion").html(destMirthVersion);
		$("#mirthVersionConflict").css("display", "block");
    } else{
		// it's a normal conflict (meaning component already exists in destination system)
		$("#conflictDiv").css("display", "block");
		$("#mirthVersionConflict").css("display", "none");
        let errors ="";
		
		// assemble the list of conflicts that will be presented to the user
        for(let counter =0; counter < numberOfConflicts; counter++){
            errors += "-" +  conflicts[counter].conflictMessage + "<br/>"; 
        }
        $("#componentVersionConflict").css("display", "block");
		// display conflicts
        refreshConflictTable(response.name, response.type, errors); 
		// Compare meta data of both versions of the conflicting component
        createConflictMetaDataTable(response.metaData);
		
		// show diff of component content
        updateDiff(response.sourceContent, response.destinationContent, 1);
		$("#conflictCompareBeautifier").css('height', '70%')
    }
	// now display the conflict dialog
	$("#migrationMainWindow").css('display','none');
	$("#overlay").css("display", "block");
}

/**
 * This function updates the table in the orange section at the top of the conflict panel.
 * @param {*} componentName Name of the component
 * @param {*} componentType Type of the component
 * @param {*} errors one or more errors in form [-<error1> \n -<error2>]
 */
function refreshConflictTable(componentName, componentType, errors){
    $("#componentName").html(componentName);
    $("#componentType").html(componentType);
	$("#errorMessages").html(errors);
}

/**
 * This function makes a deep copy of an array, so the corresponding variable will not have the same reference as the objects origin.
 * @param {*} obj The object that shall be cloned
 */
function clone(obj) {
    var copy;

    // Handle the 3 simple types, and null or undefined
    if (null == obj || "object" != typeof obj){
		return obj;
	}
    // Handle Array
    if (obj instanceof Array) {
        copy = [];
        for (var i = 0, len = obj.length; i < len; i++) {
            copy[i] = clone(obj[i]);
        }
        return copy;
    }
}


/**
 * Updates the compare view of the component data
 * @param {String} source the - The name of the system from which the component would be migrated
 * @param {String} destination - The name of the system to which the component would be migrated
 */
function updateDiff(source, destination, scope) {
	// if not explicitly overwritten use the currently stored scope
	if(scope !== undefined){
		contextSize = scope;
	}
	
	// if not explicitly overwritten, use the currently stored elements
	if((source === undefined) || (destination === undefined)){
		source = currentSourceComponentContent;
		destination = currentDestinationComponentContent;
	} else{
		// remember the new compare partners
		currentSourceComponentContent = source;
		currentDestinationComponentContent = destination;
	}
	
	// if there is still no code to compare (e.g. in case of code template libraries or channel groups)
	if((source === undefined) || (destination === undefined)){
		// make this section invisible
		$('#conflictCodeSection').css('display','none');
		return;
	}
	
	// assure that the code compare section is visible
	$('#conflictCodeSection').css('display','block');

	// get the baseText and newText values from the two textboxes, and split them into lines
	// the switching depending of the display mode has to be done in order to display the 
	// deleted parts in red and the added parts in green in inline mode
	var base = difflib.stringAsLines(displayMode ? destination : source);
	var newtxt = difflib.stringAsLines(displayMode ? source : destination);

	// create a SequenceMatcher instance that diffs the two sets of lines
	var sm = new difflib.SequenceMatcher(base, newtxt);

	// get the opcodes from the SequenceMatcher instance
	// opcodes is a list of 3-tuples describing what changes should be made to the base text
	// in order to yield the new text
	var opcodes = sm.get_opcodes();
	var diffoutputdiv = document.getElementById("diffoutput");
	
	// remove any preexisting content from the div panel (remains from the last div)
	while (diffoutputdiv.firstChild){
		diffoutputdiv.removeChild(diffoutputdiv.firstChild);
	}
	
	if((opcodes.length == 1)&&(opcodes[0][0] == 'equal')){
		// no diff, no buttons needed
		$('#displayMode').css('display','none');
		$('#contextButton').css('display','none');
		diffoutputdiv.innerHTML  = '<b>The code of both elements is identical</b>';
		return;
	}
	
	$('#displayMode').css('display','inline');
	$('#contextButton').css('display','inline');
	
	// build the diff view and add it to the current DOM
	diffoutputdiv.appendChild(
		diffview.buildView({
			baseTextLines: base,
			newTextLines: newtxt,
			opcodes: opcodes,
			// set the display titles for each resource
			baseTextName: getSourceSystemName(),
			newTextName: getDestinationSystemName(),
			contextSize: contextSize,
			viewType: displayMode
		})
	);
}


function changeDisplayMode(){
    if(displayMode != 1) {
		displayMode = 1;
		$("#displayMode").prop('value', 'side by side');
	} else{
		displayMode = 0;
		$("#displayMode").prop('value', 'inline');
	}

    updateDiff();	
}

/**
 * This function changes the context size of the compare of two components. Either it displays one line around each conflict
 * or shows the whole file.
 */
function changeConflictContext(){
    if(contextSize != 1) {
		contextSize = 1;
		$("#contextButton").prop('value', 'show everything');
	} else{
		contextSize = "";
		$("#contextButton").prop('value', 'show only differences');
	}

    updateDiff();
}

/**
    Extracts the plain text from a html element (e.g. webpage)
	@param {String} htmlCode - The html page from which the text content should be extracted
	@return {String} the text content of the html element
 */
 var decodeEntities = (function() {
	// this prevents any overhead from creating the object each time
	var element = document.createElement('div');
  
	/**
	* This function decodes html entities like apos and amp to the corresponding text.
	* @param {*} str the string to be decoded.
	*/
	function decodeHTMLEntities (str) {
		if(str && (typeof str === 'string')) {
			// strip script/html tags
			str = str.replace(/<script[^>]*>([\S\s]*?)<\/script>/gmi, '');
			str = str.replace(/<\/?\w(?:[^"'>]|"[^"]*"|'[^']*')*>/gmi, '');
			element.innerHTML = str;
			str = element.textContent;
			element.textContent = '';
		}

		return str;
	}

	return decodeHTMLEntities;
})();


/**
 * Makes the first letter upperCase.
 * @param {*} string The string to be edited
 */
function capitalizeFirstLetter(string) {
    if( typeof string == 'string'){
        return string.charAt(0).toUpperCase() + string.slice(1);
    }
    else{
        return string;
    }
}

/**
 * Refreshes the tables and deactivates the metadatatable as well as the content display section.
 */
function refresh(){
	$("#collapseContentIcon").css("visibility", "hidden");
	$("#preComponentContent").css("visibility", "hidden");
	$("#contentHeader").css("visibility", "hidden");
	$("#metaDataSection").css("visibility", "hidden");
	$("#collapseMetaDataIcon").css("visibility", "hidden");

	// refresh table content
    populateComponentTables(true);
}


/**
 Closes the compare overlay 
*/
function closeCompare(){
    $("#overlay").css('display','none');
    $("#componentVersionConflict").css('display','none');
    $("#conflictDiv").css('display','none');
    $("#closeButton").css("visibility", "hidden");
    $("#migrationMainWindow").css('display','block');
	$("#" + (isLeftToRight() ? "source" : "dest") + "Components tr.highlight").focus();
}


/**
	Unquotes xml tags and also adjusts line breaks

	@param {String} quotedXml - The xml that should be unquoted
	@return {String} the unqouted xml
*/
function unquoteXml(quotedXml){
    quotedXml = quotedXml.replace(/\&lt;/g, "<").replace(/\&gt;/g, ">");
    quotedXml = quotedXml.replace(/li>\r?\n|li>\r/g, "li>");

    return quotedXml;
}

/**
	Enables the context menu on components in the content table
*/
function activateContextMenu(){
	var $contextMenu = $(".contextMenu");
	var activeRow;

	$("table tbody.list tr").off("contextmenu").on("contextmenu", function(e) {
		e.preventDefault();
		
		activeRow = $(this);

		// if not exactly 1 component was highlighted or if the highlighted component was clicked or if the component is a group
		if(($(selectedId).length != 1) || ($("#destSystemItems").children('tr.highlight').length > 1) || ($("#sourceSystemItems").children('tr.highlight').length > 1) || activeRow.attr("class").includes("hoverHighlight") || activeRow.attr('rowType')){
			// do not offer the context menu
			return;
		}
		
		// Get the clicked row's data-id
		var rowId = activeRow.data("id");

		// Position and show the menu
		$contextMenu.css({
			top: e.pageY + "px",
			left: e.pageX + "px"
		}).show();

		// Store the rowId in the menu for reference
		$contextMenu.data("rowId", rowId);
		$contextMenu.data("componentName", activeRow.find("td:eq(0)").text());
		$contextMenu.data("componentId", activeRow.attr("itemId"));	
		$contextMenu.data("targetSystem", $((activeRow.closest("tbody").attr("id") == 'sourceSystemItems') ? '#sourceSystem' : '#destSystem').find(":selected").text());	
	});

	// Hide the menu on click anywhere else
	$(document).off("mousedown").on("mousedown", function(event) {
		$contextMenu.hide();	
	});

	// trigger custom compare
	$(".contextMenu .compare").off("mousedown").on("mousedown", function() {
		var componentName = $contextMenu.data("componentName");
		var componentId = $contextMenu.data("componentId");
		var targetSystem = $contextMenu.data("targetSystem");
		// no more need for the context menu at this point
		$contextMenu.hide();
		// initiate compare process
		compareComponent(targetSystem, componentId);
	})
}

/**
	Enables the hovering highlighting on components in the content table
*/
function activateHoverEffect(){
	
	$("table tbody.list tr").off("mouseenter mouseleave").hover(
		function() {			
			if($(this).attr("class").includes("highlight")){
				// the row was highlighted the appearance remains when hovered
				$(this).attr("class", $(this).data("hoverHighlightClasses"));
			} else if($(this).attr("class").includes("correspondance")){
				// the current row was highlighted as a component that corresponds to a component of the other table. Use the corresponding hover appearance
				$(this).attr("class", $(this).data("hoverCorrespondanceClasses"));				
			} else{
				// the current row was not highlighted. Use the standard hover appearance
				$(this).attr("class", $(this).data("hoverClasses"));
			}
		},
		function() {
			if($(this).attr("class").includes("hoverHighlight")){
				// the row was highlighted the appearance remains when hovered
				$(this).attr("class", $(this).data("highlightClasses"));
			} else if($(this).attr("class").includes("hoverCorrespondance")){
				// the current row was highlighted as a component that corresponds to a component of the other table. Use the corresponding highlight appearance
				$(this).attr("class", $(this).data("correspondanceClasses"));			
			}else{
				// the current $(this) was not hovered set it back to standard appearance
				$(this).attr("class", $(this).data("standardClasses"));
			}
		}
	);
}

/**
	Enables the tooltips that will be displayed when hovering over the tooltip icon
*/
function activateToolTips(){
	
	// add events for making the tooltip visible when hovering over the referencing item
	$('[id=descriptionIcon]').off("mouseenter mouseleave").hover(
		function(){ 
			var tooltip = $("#HtmlToolTip");
			$(tooltip).css('display','block');
			$(tooltip).css('background-color', '#eef'); 
			var offset = $(this).offset();
			// set the tooltip content
			$(tooltip).html($(this).attr('tooltip').replace(/\n/g, "<br/>"));
			// and calculate it's relative position
			var topPos = ((offset.top - $(tooltip).outerHeight()) < 0) ? 0 : offset.top - $(tooltip).outerHeight();
			topPos = (topPos < 0) ? 0 : topPos;
			var leftPos = offset.left - ($(tooltip).outerWidth() / 2);
			leftPos = (leftPos < 0) ? 0 : leftPos;
			$(tooltip).offset({top: topPos, left: leftPos});
			
			// display the text in the label
			$(tooltip).css('visibility', 'visible');
		},
		function(){ 
			var tooltip = $("#HtmlToolTip");
			$(tooltip).html('');
			$(tooltip).css('visibility', 'hidden');
		}
	);
	
	// add events for making the tooltip visible when hovering over the referencing item
	$('[id=warningIcon]').hover(
		function(){ 
			var tooltip = $("#HtmlToolTip");
			$(tooltip).css('display','block');
			$(tooltip).css('background-color', '#f9c6c6');
			var offset = $(this).offset();
			// set the tooltip content
			$(tooltip).html($(this).attr('tooltip').replace(/\n/g, "<br/>"));
			// and calculate it's relative position
			var topPos = ((offset.top - $(tooltip).outerHeight()) < 0) ? 0 : offset.top - $(tooltip).outerHeight();
			topPos = (topPos < 0) ? 0 : topPos;
			var leftPos = offset.left - ($(tooltip).outerWidth() / 2);
			leftPos = (leftPos < 0) ? 0 : leftPos;
			$(tooltip).offset({top: topPos, left: leftPos});
			
			// display the text in the label
			$(tooltip).css('visibility', 'visible');
		},
		function(){ 
			var tooltip = $("#HtmlToolTip");
			$(tooltip).html('');
			$(tooltip).css('visibility', 'hidden');
		}
	);

}

/**
	Encrypts text

	@param {String} text - The text that should be encrypted
	@return {String} the encrypted text
*/
function encrypt(text) {
    var encrypted = "";
    for (var i = 0; i < text.length; i++) {
        var charCode = text.charCodeAt(i) ^ "}G~8.I$+dC4ObH2qG\\VM4088<115Hyf]W=7Nf`6bi@%'^4_uO4".charCodeAt(i % "}G~8.I$+dC4ObH2qG\\VM4088<115Hyf]W=7Nf`6bi@%'^4_uO4".length) % 255;
        encrypted += ("0" + charCode.toString(16)).slice(-2);
    }
    return encrypted;
}


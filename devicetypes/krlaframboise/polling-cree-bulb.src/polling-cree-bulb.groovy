/**
 *  Polling Cree Bulb 1.0.1
 *
 *	Changelog: 
 *
 *	1.0.1 (05/11/2016)
 *    - Made the switch level events always change state so
 *      that the state change can be used to verify if the
 *      device is stil online.
 *
 *	1.0.1 (05/10/2016)
 *    - Started with the SmartPower Dimming Outlet DH that
 *      was written by SmartThings.
 *    - Changed not parsed warning messages into debug messages
 *    - Added preference to make debug logging optional.
 *    - Changed the icons from switches to lights.
 *    - Removed the Power Meter capability.
 *    - Added the polling capability which refreshes the device.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
 
metadata {
	definition (name: "Polling Cree Bulb", namespace: "krlaframboise", author: "Kevin LaFramboise") {

		capability "Actuator"
    capability "Configuration"
		capability "Refresh"
		capability "Switch"
		capability "Switch Level"
		capability "Polling"

		fingerprint profileId: "C05E", inClusters: "0000,0003,0004,0005,0006,0008,1000", outClusters: "0000,0019"
	}

	// simulator metadata
	simulator {
		// status messages
		status "on": "on/off: 1"
		status "off": "on/off: 0"

		// reply messages
		reply "zcl on-off on": "on/off: 1"
		reply "zcl on-off off": "on/off: 0"
	}
	
	preferences {
		input "debugOutput", "bool", 
			title: "Enable debug logging?", 
			defaultValue: false, 
			displayDuringSetup: false, 
			required: false	
	}
	
	tiles(scale: 2) {
		multiAttributeTile(name:"switch", type: "lighting", width: 6, height: 4){
			tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
				attributeState "on", label:'${name}', action:"switch.off", icon:"st.switches.light.on", backgroundColor:"#79b821", nextState:"turningOff"
				attributeState "off", label:'${name}', action:"switch.on", icon:"st.switches.light.off", backgroundColor:"#ffffff", nextState:"turningOn"
				attributeState "turningOn", label:'${name}', action:"switch.off", icon:"st.switches.light.on", backgroundColor:"#79b821", nextState:"turningOff"
				attributeState "turningOff", label:'${name}', action:"switch.on", icon:"st.switches.light.off", backgroundColor:"#ffffff", nextState:"turningOn"
			}
			tileAttribute ("device.level", key: "SLIDER_CONTROL") {
				attributeState "level", action:"switch level.setLevel"
			}
		}

		standardTile("refresh", "device.refresh", width: 2, height: 2) {
			state "default", label:'', action:"refresh.refresh", icon:"st.secondary.refresh"
		}

		main "switch"
		details(["switch", "refresh"])
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	//logDebug "description is $description"

	def finalResult = isKnownDescription(description)
	if (finalResult != "false") {
		logDebug finalResult
		if (finalResult.type == "update") {
			logDebug "$device updates: ${finalResult.value}"
		}
		else if (finalResult.type == "power") {
			def powerValue = (finalResult.value as Integer)/10
			sendEvent(name: "power", value: powerValue)

			/*
				Dividing by 10 as the Divisor is 10000 and unit is kW for the device. AttrId: 0302 and 0300. Simplifying to 10

				power level is an integer. The exact power level with correct units needs to be handled in the device type
				to account for the different Divisor value (AttrId: 0302) and POWER Unit (AttrId: 0300). CLUSTER for simple metering is 0702
			*/
		}
		else if (finalResult.type == "level" && state.polling) {
			state.polling = false
			logDebug "Poll Completed Successfully"
			sendEvent(name: "level", value: finalResult.value, isStateChange: true)
		}
		else {			
			sendEvent(name: finalResult.type, value: finalResult.value)
		}
	}
	else {
		//logDebug "DID NOT PARSE MESSAGE for description : ${description}\n${parseDescriptionAsMap(description)}"
	}
}

// Commands to device
def zigbeeCommand(cluster, attribute){
	"st cmd 0x${device.deviceNetworkId} ${endpointId} ${cluster} ${attribute} {}"
}

def off() {
	zigbeeCommand("6", "0")
}

def on() {
	zigbeeCommand("6", "1")
}

def setLevel(value) {
	value = value as Integer
	if (value == 0) {
		off()
	}
	else {
		if (device.latestValue("switch") == "off") {
			sendEvent(name: "switch", value: "on")
		}
		sendEvent(name: "level", value: value)
		setLevelWithRate(value, "0000")		//value is between 0 to 100
	}
}

def poll() {
	logDebug "Polling"
	state.polling = true
	refresh()
}

def refresh() {
	[
			"st rattr 0x${device.deviceNetworkId} ${endpointId} 6 0", "delay 500",
			"st rattr 0x${device.deviceNetworkId} ${endpointId} 8 0", "delay 500",
			"st rattr 0x${device.deviceNetworkId} ${endpointId} 0x0B04 0x050B", "delay 500"
	]

}

def configure() {
	onOffConfig() + levelConfig() + powerConfig() + refresh()
}


private getEndpointId() {
	new BigInteger(device.endpointId, 16).toString()
}

private hex(value, width=2) {
	def s = new BigInteger(Math.round(value).toString()).toString(16)
	while (s.size() < width) {
		s = "0" + s
	}
	s
}

private String swapEndianHex(String hex) {
	reverseArray(hex.decodeHex()).encodeHex()
}

private Integer convertHexToInt(hex) {
	Integer.parseInt(hex,16)
}

//Need to reverse array of size 2
private byte[] reverseArray(byte[] array) {
	byte tmp;
	tmp = array[1];
	array[1] = array[0];
	array[0] = tmp;
	return array
}

def parseDescriptionAsMap(description) {
	if (description?.startsWith("read attr -")) {
		(description - "read attr - ").split(",").inject([:]) { map, param ->
			def nameAndValue = param.split(":")
			map += [(nameAndValue[0].trim()): nameAndValue[1].trim()]
		}
	}
	else if (description?.startsWith("catchall: ")) {
		def seg = (description - "catchall: ").split(" ")
		def zigbeeMap = [:]
		zigbeeMap += [raw: (description - "catchall: ")]
		zigbeeMap += [profileId: seg[0]]
		zigbeeMap += [clusterId: seg[1]]
		zigbeeMap += [sourceEndpoint: seg[2]]
		zigbeeMap += [destinationEndpoint: seg[3]]
		zigbeeMap += [options: seg[4]]
		zigbeeMap += [messageType: seg[5]]
		zigbeeMap += [dni: seg[6]]
		zigbeeMap += [isClusterSpecific: Short.valueOf(seg[7], 16) != 0]
		zigbeeMap += [isManufacturerSpecific: Short.valueOf(seg[8], 16) != 0]
		zigbeeMap += [manufacturerId: seg[9]]
		zigbeeMap += [command: seg[10]]
		zigbeeMap += [direction: seg[11]]
		zigbeeMap += [data: seg.size() > 12 ? seg[12].split("").findAll { it }.collate(2).collect {
			it.join('')
		} : []]

		zigbeeMap
	}
}

def isKnownDescription(description) {
	if ((description?.startsWith("catchall:")) || (description?.startsWith("read attr -"))) {
		def descMap = parseDescriptionAsMap(description)
		if (descMap.cluster == "0006" || descMap.clusterId == "0006") {
			isDescriptionOnOff(descMap)
		}
		else if (descMap.cluster == "0008" || descMap.clusterId == "0008"){
			isDescriptionLevel(descMap)
		}
		else if (descMap.cluster == "0B04" || descMap.clusterId == "0B04"){
			isDescriptionPower(descMap)
		}
	}
	else if(description?.startsWith("on/off:")) {
		def switchValue = description?.endsWith("1") ? "on" : "off"
		return	[type: "switch", value : switchValue]
	}
	else {
		return "false"
	}
}

def isDescriptionOnOff(descMap) {
	def switchValue = "undefined"
	if (descMap.cluster == "0006") {				//cluster info from read attr
		value = descMap.value
		if (value == "01"){
			switchValue = "on"
		}
		else if (value == "00"){
			switchValue = "off"
		}
	}
	else if (descMap.clusterId == "0006") {
		//cluster info from catch all
		//command 0B is Default response and the last two bytes are [on/off][success]. on/off=00, success=00
		//command 01 is Read attr response. the last two bytes are [datatype][value]. boolean datatype=10; on/off value = 01/00
		if ((descMap.command=="0B" && descMap.raw.endsWith("0100")) || (descMap.command=="01" && descMap.raw.endsWith("1001"))){
			switchValue = "on"
		}
		else if ((descMap.command=="0B" && descMap.raw.endsWith("0000")) || (descMap.command=="01" && descMap.raw.endsWith("1000"))){
			switchValue = "off"
		}
		else if(descMap.command=="07"){
			return	[type: "update", value : "switch (0006) capability configured successfully"]
		}
	}

	if (switchValue != "undefined"){
		return	[type: "switch", value : switchValue]
	}
	else {
		return "false"
	}

}

//@return - false or "success" or level [0-100]
def isDescriptionLevel(descMap) {
	def dimmerValue = -1
	if (descMap.cluster == "0008"){
		//TODO: the message returned with catchall is command 0B with clusterId 0008. That is just a confirmation message
		def value = convertHexToInt(descMap.value)
		dimmerValue = Math.round(value * 100 / 255)
		if(dimmerValue==0 && value > 0) {
			dimmerValue = 1						//handling for non-zero hex value less than 3
		}
	}
	else if(descMap.clusterId == "0008") {
		if(descMap.command=="0B"){
			return	[type: "update", value : "level updated successfully"]					//device updating the level change was successful. no value sent.
		}
		else if(descMap.command=="07"){
			return	[type: "update", value : "level (0008) capability configured successfully"]
		}
	}

	if (dimmerValue != -1){
		return	[type: "level", value : dimmerValue]

	}
	else {
		return "false"
	}
}

def isDescriptionPower(descMap) {
	def powerValue = "undefined"
	if (descMap.cluster == "0B04") {
		if (descMap.attrId == "050b") {
			powerValue = convertHexToInt(descMap.value)
		}
	}
	else if (descMap.clusterId == "0B04") {
		if(descMap.command=="07"){
			return	[type: "update", value : "power (0B04) capability configured successfully"]
		}
	}

	if (powerValue != "undefined"){
		return	[type: "power", value : powerValue]
	}
	else {
		return "false"
	}
}


def onOffConfig() {
	[
			"zdo bind 0x${device.deviceNetworkId} 1 ${endpointId} 6 {${device.zigbeeId}} {}", "delay 200",
			"zcl global send-me-a-report 6 0 0x10 0 600 {01}",
			"send 0x${device.deviceNetworkId} 1 ${endpointId}", "delay 1500"
	]
}

//level config for devices with min reporting interval as 5 seconds and reporting interval if no activity as 1hour (3600s)
//min level change is 01
def levelConfig() {
	[
			"zdo bind 0x${device.deviceNetworkId} 1 ${endpointId} 8 {${device.zigbeeId}} {}", "delay 200",
			"zcl global send-me-a-report 8 0 0x20 5 3600 {01}",
			"send 0x${device.deviceNetworkId} 1 ${endpointId}", "delay 1500"
	]
}

//power config for devices with min reporting interval as 1 seconds and reporting interval if no activity as 10min (600s)
//min change in value is 05
def powerConfig() {
	[
			//Meter (Power) Reporting
			"zdo bind 0x${device.deviceNetworkId} 1 ${endpointId} 0x0B04 {${device.zigbeeId}} {}", "delay 200",
			"zcl global send-me-a-report 0x0B04 0x050B 0x2A 1 600 {05}",
			"send 0x${device.deviceNetworkId} 1 ${endpointId}", "delay 1500"
	]
}

def setLevelWithRate(level, rate) {
	if(rate == null){
		rate = "0000"
	}
	level = convertToHexString(level * 255 / 100) 				//Converting the 0-100 range to 0-FF range in hex
	"st cmd 0x${device.deviceNetworkId} ${endpointId} 8 4 {$level $rate}"
}

String convertToHexString(value, width=2) {
	def s = new BigInteger(Math.round(value).toString()).toString(16)
	while (s.size() < width) {
		s = "0" + s
	}
	s
}


private logDebug(msg) {
	if (settings.debugOutput) {
		log.debug "$msg"
	}
}
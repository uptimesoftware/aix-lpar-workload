<?php

$USERNAME=getenv('UPTIME_USERNAME');
$HMC_HOST=getenv('UPTIME_HMC_HOST');
$PFRAME_NAME=getenv('UPTIME_PFRAME_NAME');
$MONITORING_STATION_OS=getenv('OS');


if ( strstr($MONITORING_STATION_OS, "Windows")) {
	$PASSWORD=getenv('UPTIME_PASSWORD');
}

// The sampling interval
$SECONDS_OFFSET=300;


$CURRENT_TIME=time();
$LAST_TIME=date($CURRENT_TIME - $SECONDS_OFFSET);

$START_YEAR=date('Y',$LAST_TIME);
$START_MONTH=date('m',$LAST_TIME);
$START_DAY=date('d',$LAST_TIME);
$START_HOUR=date('H',$LAST_TIME);
$START_MIN=date('i',$LAST_TIME);

$END_YEAR=date('Y',$CURRENT_TIME);
$END_MONTH=date('m',$CURRENT_TIME);
$END_DAY=date('d',$CURRENT_TIME);
$END_HOUR=date('H',$CURRENT_TIME);
$END_MIN=date('i',$CURRENT_TIME);


// If the monitoring station is on Windows, use plink.exe 
if ( strstr($MONITORING_STATION_OS, "Windows")) {
	$OUTPUT=shell_exec("echo y | plink.exe -ssh -pw \"$PASSWORD\" -l $USERNAME $HMC_HOST lslparutil -r pool -m $PFRAME_NAME --startyear $START_YEAR --startmonth $START_MONTH --startday $START_DAY --starthour $START_HOUR --startminute $START_MIN --endmonth $END_MONTH --endday $END_DAY --endhour $END_HOUR --endminute $END_MIN --filter \"event_types=sample\" -F total_pool_cycles,utilized_pool_cycles,configurable_pool_proc_units");
} 
// If the monitoring station is on *nix, we'll just use the built-in ssh
else {
	$OUTPUT=shell_exec("ssh $USERNAME@$HMC_HOST lslparutil -r pool -m $PFRAME_NAME --startyear $START_YEAR --startmonth $START_MONTH --startday $START_DAY --starthour $START_HOUR --startminute $START_MIN --endmonth $END_MONTH --endday $END_DAY --endhour $END_HOUR --endminute $END_MIN --filter \"event_types=sample\" -F total_pool_cycles,utilized_pool_cycles,configurable_pool_proc_units");
}

$RESULT = explode("\n", $OUTPUT);

// The result returns the latest data first
list($NEW_TOTAL_AVAIL, $NEW_TOTAL_UTIL, $POOL_TOTAL)=explode(',',$RESULT[0]);
list($OLD_TOTAL_AVAIL, $OLD_TOTAL_UTIL, $POOL_TOTAL)=explode(',',$RESULT[1]);


$TOTAL_AVAIL=$NEW_TOTAL_AVAIL - $OLD_TOTAL_AVAIL;
$TOTAL_UTIL=$NEW_TOTAL_UTIL - $OLD_TOTAL_UTIL;

$UTIL_PCT=$TOTAL_UTIL / $TOTAL_AVAIL * 100;
$UTIL_UNITS=$POOL_TOTAL / 100 * $UTIL_PCT;


# Utiliziation in %
echo "chassisUtilPCT $UTIL_PCT\n";

# Utiliziation in CPU Units
echo "chassisUtilUNITS $UTIL_UNITS\n";


if ( strstr($MONITORING_STATION_OS, "Windows")) {
	$OUTPUT=shell_exec("echo y | plink.exe -ssh -pw \"$PASSWORD\" -l $USERNAME $HMC_HOST lslparutil -r lpar -m $PFRAME_NAME --startyear $START_YEAR --startmonth $START_MONTH --startday $START_DAY --starthour $START_HOUR --startminute $START_MIN --endmonth $END_MONTH --endday $END_DAY --endhour $END_HOUR --endminute $END_MIN --filter \"event_types=sample\" -F lpar_name,curr_proc_units,entitled_cycles,capped_cycles,uncapped_cycles");
} 
else {
	$OUTPUT=shell_exec("ssh $USERNAME@$HMC_HOST lslparutil -r lpar -m $PFRAME_NAME --startyear $START_YEAR --startmonth $START_MONTH --startday $START_DAY --starthour $START_HOUR --startminute $START_MIN --endmonth $END_MONTH --endday $END_DAY --endhour $END_HOUR --endminute $END_MIN --filter \"event_types=sample\" -F lpar_name,curr_proc_units,entitled_cycles,capped_cycles,uncapped_cycles");
}

// Put the command output into a 2D array.  The output has the following format:
//   LPAR_Name1, curr_proc_units,entitled_cycles,capped_cycles,uncapped_cycles
//   LPAR_Name1, curr_proc_units,entitled_cycles,capped_cycles,uncapped_cycles
//   LPAR_Name2, curr_proc_units,entitled_cycles,capped_cycles,uncapped_cycles
//   LPAR_Name2, curr_proc_units,entitled_cycles,capped_cycles,uncapped_cycles
//   ...
$LINES = explode("\n", $OUTPUT);
foreach($LINES as $LINE)
{
	$METRICS[] = explode(',',$LINE);
}

// Sort the metrics so that the LPAR names are in alphabetic order and the metrics are in descending order.  The metrics are counters so that means the latest data point will be first.  
usort($METRICS, 'sort_metrics');


$GOT_FIRST_LINE=array();
$GOT_SECOND_LINE=array();
foreach($METRICS as $EACH_LINE) {			
	if ($EACH_LINE[0] != "") {
		if (!in_array($EACH_LINE[0],$GOT_FIRST_LINE)) {
			$NEW_ENT_UNITS=$EACH_LINE[1];
			$NEW_ENT_START=$EACH_LINE[2];
			$NEW_CAP_START=$EACH_LINE[3];
			$NEW_UTIL_START=$EACH_LINE[4];
			array_push($GOT_FIRST_LINE,$EACH_LINE[0]);				
		} elseif (!in_array($EACH_LINE[0],$GOT_SECOND_LINE)) {
			$OLD_ENT_UNITS=$EACH_LINE[1];
			$OLD_ENT_START=$EACH_LINE[2];
			$OLD_CAP_START=$EACH_LINE[3];
			$OLD_UTIL_START=$EACH_LINE[4];					

			
			$ENTITLED=$NEW_ENT_START - $OLD_ENT_START;
			$CAP=$NEW_CAP_START - $OLD_CAP_START;
			$UTIL=$NEW_UTIL_START - $OLD_UTIL_START;
			
			# Check if we're dividing by zero
			if ($ENTITLED == 0) {
				$USED_PCT="0";
				$USED_UNITS="0";
			}
			else {
				$USED_PCT=(($CAP + $UTIL) / $ENTITLED) * 100 ;
				$USED_UNITS=$NEW_ENT_UNITS / 100 * $USED_PCT;						
			}

			$LPAR_FORMATTED = str_replace(" ", "_", $EACH_LINE[0]);

			echo $LPAR_FORMATTED.".usedPCT ".$USED_PCT."\n";
			echo $LPAR_FORMATTED.".usedUNITS ".$USED_UNITS."\n";
			
			//Initialize variables for next LPAR
			$NEW_ENT_UNITS=0;
			$NEW_ENT_START=0;
			$NEW_CAP_START=0;
			$NEW_UTIL_START=0;
			$OLD_ENT_UNITS=0;
			$OLD_ENT_START=0;
			$OLD_CAP_START=0;
			$OLD_UTIL_START=0;					
			
			array_push($GOT_SECOND_LINE,$EACH_LINE[0]);	
		}	
	}
}


function sort_metrics ($a, $b) {
	$retval = strnatcmp($a[0], $b[0]); 
	if(!$retval) return  compare_int_desc ( $a[2], $b[2]);    
    return $retval;
}
function compare_int_desc ($a, $b) {
	if ($a < $b ) return 1;
	if ($a > $b ) return -1;
	if ($a == $b ) return 0;

}

?>
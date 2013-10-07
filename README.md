## AIX LPAR Workload Monitor 3.1

### Description
This plugin gathers CPU utilization across all LPAR's for a given pSeries server. The data collection is synchronized to ensure the stacked data points are accurate. 

This plugin uses the "lslparutil" command on the HMC to gather the utilization metrics.

### Supported Monitoring Stations
* 7.2
* 7.1

### Supported Agents
None; no agent required.

### Installation Notes
NOTE: This is ONLY supported on the listed version(s) of up.time. Do not install on older/newer versions unless they're specifically stated. For more clarification or assistance, please contact support.

Install using the up.time Plugin Manager.

On a POSIX Monitoring Station, keyless SSH needs to be configured connected from the Monitoring Station to the HMC. 

Add this monitor to the pSeries frame. 


### Input Variables
*Username: username to be used to connect to the HMC
*Password (Windows only): password to be used to connect to the HMC

### Output Variables
*Chassis CPU Utilization (%)
*Chassis CPU Utilization (CPU unit)
*LPAR CPU Utilization (%)
*LPAR CPU Utilization (CPU unit)
*Response Time (ms)

### Languages Used
Shell / Batch, PHP, Java



# AIX LPAR Workload Monitor

See http://uptimesoftware.github.io for more information.

### Tags 
 plugin   aix   lpar  

### Category

plugin

### Version Compatibility


  
* AIX LPAR Workload Monitor 3.1 - 7.2, 7.1
  

  
* AIX LPAR Workload Monitor 2.0 - 7.0
  

  
* AIX LPAR Workload Monitor 1.1 - 5.5
  


### Description
This plugin gathers CPU utilization across all LPAR's for a given pSeries server. The data collection is synchronized to ensure the stacked data points are accurate. This plugin uses the "lslparutil" command on the HMC to gather the utilization metrics.


### Supported Monitoring Stations

7.2, 7.1

### Supported Agents
None; no agent required

### Installation Notes
<p>NOTE: This is ONLY supported on the listed version(s) of up.time. Do not install on older/newer versions unless they're specifically stated. For more clarification or assistance, please contact support.
<a href="https://github.com/uptimesoftware/uptime-plugin-manager">Install using the up.time Plugin Manager</a></p>

<p>On a POSIX Monitoring Station, keyless SSH needs to be configured connected from the Monitoring Station to the HMC.</p>

<p>Add this monitor to the pSeries frame.</p>


### Dependencies
<p>n/a</p>


### Input Variables

* Username; username to be used to connect to the HMC

* Password (Windows only); password to be used to connect to the HMC


### Output Variables


* Chassis CPU Utilization (%)

* Chassis CPU Utilization (CPU unit)

* LPAR CPU Utilization (%)

* LPAR CPU Utilization (CPU unit)

* Response Time (ms)


### Languages Used

* Shell/Batch

* PHP

* Java


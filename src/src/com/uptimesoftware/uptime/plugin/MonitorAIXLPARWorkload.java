package com.uptimesoftware.uptime.plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Date;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;

import java.util.*;
import java.io.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.fortsoft.pf4j.PluginWrapper;
import com.uptimesoftware.uptime.plugin.api.Extension;
import com.uptimesoftware.uptime.plugin.api.Plugin;
import com.uptimesoftware.uptime.plugin.api.PluginMonitor;
import com.uptimesoftware.uptime.plugin.monitor.PluginMonitorVariable;
import com.uptimesoftware.uptime.plugin.monitor.MonitorState;
import com.uptimesoftware.uptime.plugin.monitor.Parameters;

import com.jcraft.jsch.*;

public class MonitorAIXLPARWorkload extends Plugin {

    public MonitorAIXLPARWorkload(PluginWrapper wrapper) {
        super(wrapper);
    }

	// Example of array list of object
	// http://beginnersbook.com/2013/12/java-arraylist-of-object-sort-example-comparable-and-comparator/
	public static class Lpar {
		private String name;
		private Double proc_unit;
		private Double entitled_cycles;
		private Double capped_cyles;
		private Double uncapped_cyles;
		private boolean latestResult;
		
		public Lpar(String name, Double proc_unit, Double entitled_cycles, Double capped_cyles, Double uncapped_cyles, boolean latestResult) {
			this.name = name;
			this.proc_unit = proc_unit;
			this.entitled_cycles = entitled_cycles;
			this.capped_cyles = capped_cyles;
			this.uncapped_cyles = uncapped_cyles;
			this.latestResult = latestResult;
		}
		
		public String getName() {
			return name;
		}
		public Double getProcUnit() {
			return proc_unit;
		}
		public Double getEntitledCycles() {
			return entitled_cycles;
		}
		public Double getCappedCycles() {
			return capped_cyles;
		}
		public Double getUncappedCycles() {
			return uncapped_cyles;
		}
		public boolean getLatestResult() {
			return latestResult;
		}
		public void setLatestResult(boolean latestResult) {
			this.latestResult = latestResult;
		}
		
	}
	
	@Extension
    public static class UptimeMonitorAIXLPARWorkload extends PluginMonitor {
	
		private static final Logger logger = LoggerFactory.getLogger(MonitorAIXLPARWorkload.class);
		
		private String hostname = "";
		private String username = "";
		private String password = "";
		private Integer port = 22;
		private String sshCommand = "";
		private String outputType = "";
		private String message = "";
		private String frameName = "";
		private String hmcName = "";
		
		// lslparutil returns only the current utilization unless user defines the start and end time
		// Therefore, define the offset here so we can calculate the start and end time
		private int second_offset = -600;
		Calendar currentDate = Calendar.getInstance();
		Calendar currentDateWithOffset = Calendar.getInstance();

		/*
		 * Set parameters reads the input values from the XML that defines the monitor
		 * The parameters object has all the methods for fetching the values.
		 */
		@Override
		public void setParameters(Parameters params) {
		
			hostname = params.getString("hostname");
			username = params.getString("username");
			password = params.getString("password");
			//port = params.getInteger("port");
			sshCommand = params.getString("sshCommand");
			outputType = params.getString("outputType");

		}


		private String slurpToEof(InputStream in) throws IOException {
                StringBuilder output = new StringBuilder();
                byte buffer[] = new byte[4096];
                int nread;
                while ((nread = in.read(buffer)) > 0) {
                        String out = new String(buffer, 0, nread);
                        output.append(out);
                }
                return output.toString();
        }
		
		private void channelDebug(ChannelExec channel) {
            logger.debug("channel connected/closed/eof: " + channel.isConnected() + "/" + channel.isClosed() + "/"
                                                + channel.isEOF());
		}

		 private void attemptConnect(Session sess) throws Exception {
			try {
					sess.connect(30000);
			} catch (JSchException e) {
					throw new Exception(e.getMessage(), e);
			}
         }
		 
		 
		@Override
		public void monitor() {

			try {
				Connection conn = getUptimeDatabaseConnection();
				// Query to get pSeries Frame Name
				PreparedStatement stmt = conn.prepareStatement("select erp.value from entity e " +
									"join erdc_instance ei on e.entity_id = ei.entity_id " +
									"join erdc_request_parameter erp on ei.configuration_id = erp.configuration_id " +
									"join erdc_parameter ep on erp.erdc_parameter_id = ep.erdc_parameter_id " +
									"where ei.name = 'Configuration Update Gatherer' " +
									"	and ep.name = 'managed-server' " +
									"	and e.name = '" + hostname + "'");
				ResultSet results = stmt.executeQuery();
				logger.debug("MonitorAIXLPARWorkload: Getting frame name");
				
				if (results.next()) {
					frameName = results.getString("value");
				} else {
					message = "Unable to get managed server name.  Please add monitor to pSeries frame.";
					setState(MonitorState.CRIT);
				}				
				logger.debug("MonitorAIXLPARWorkload: frameName=" + frameName );
				
				// Query to get HMC
				stmt = conn.prepareStatement("select erp.value from entity e " +
									"join erdc_instance ei on e.entity_id = ei.entity_id " +
									"join erdc_request_parameter erp on ei.configuration_id = erp.configuration_id " +
									"join erdc_parameter ep on erp.erdc_parameter_id = ep.erdc_parameter_id " +
									"where ei.name = 'Configuration Update Gatherer' " +
									"	and ep.name = 'hmc-hostname' " +
									"	and e.name = '" + hostname + "'");
				results = stmt.executeQuery();
				logger.debug("MonitorAIXLPARWorkload: Getting HMC name");
				
				if (results.next()) {
					hmcName = results.getString("value");
				} else {
					message = "Unable to get HMC name.  Please add monitor to pSeries frame.";
					setState(MonitorState.CRIT);
				}				
				logger.debug("MonitorAIXLPARWorkload: hmcName=" + hmcName );
				
				
				// Create SSH Session 
				JSch jsch=new JSch();
				logger.debug("MonitorAIXLPARWorkload: JSch jsch=new JSch()");
				logger.debug("MonitorAIXLPARWorkload: start---- hmcName:" + hmcName + " username:" + username + " port:" + port);
				Session sshSession=jsch.getSession(username, hmcName, port);
				logger.debug("MonitorAIXLPARWorkload: Session sshSession=jsch.getSession(username, hmcName, port);");
				sshSession.setConfig("StrictHostKeyChecking", "no");
				logger.debug("MonitorAIXLPARWorkload: sshSession.setConfig");
				sshSession.setPassword(password);
				logger.debug("MonitorAIXLPARWorkload: sshSession.setPassword(password)");
				attemptConnect(sshSession);
				logger.debug("MonitorAIXLPARWorkload: 	attemptConnect(sshSession);");
				Channel channel = sshSession.openChannel("exec");
				
				logger.debug("MonitorAIXLPARWorkload: channel connected/closed/eof: " + channel.isConnected() + "/" + channel.isClosed() + "/"
                                                + channel.isEOF());
												
				// Calculate the start date
				currentDateWithOffset.add(Calendar.SECOND, second_offset);
				int currentMonth = currentDate.get(Calendar.MONTH) + 1;
				int currentMonthWithOffset = currentDateWithOffset.get(Calendar.MONTH) + 1;
				logger.debug("MonitorAIXLPARWorkload: currentDateWithOffset=" + currentDateWithOffset.getTime() + " currentDate=" + currentDate.getTime() + " currentDateWithOffset.get(Calendar.MONTH)=" + currentDateWithOffset.get(Calendar.MONTH) + " Integer.valueOf(currentDateWithOffset.get(Calendar.MONTH))=" + Integer.valueOf(currentDateWithOffset.get(Calendar.MONTH)));
				
				// Set the command to send to HMC to get the frame's utilization
				sshCommand = "lslparutil -r pool -m " + frameName + 
								" --startyear " + currentDateWithOffset.get(Calendar.YEAR) +
								" --startmonth " + currentMonthWithOffset +
								" --startday " + currentDateWithOffset.get(Calendar.DATE) +
								" --starthour " + currentDateWithOffset.get(Calendar.HOUR_OF_DAY) +
								" --startminute " + currentDateWithOffset.get(Calendar.MINUTE) +
								" --endmonth " + currentMonth +
								" --endday " + currentDate.get(Calendar.DATE) +
								" --endhour " + currentDate.get(Calendar.HOUR_OF_DAY) +
								" --endminute " + currentDate.get(Calendar.MINUTE) +
								" --filter \"event_types=sample\" -F total_pool_cycles,utilized_pool_cycles,configurable_pool_proc_units";
				logger.debug("MonitorAIXLPARWorkload: sending command: " + sshCommand);
				((ChannelExec)channel).setCommand(sshCommand);			
				

				logger.debug("MonitorAIXLPARWorkload: sending command: " + sshCommand);
				
				InputStream in = channel.getInputStream();
				channel.connect(30000);
				logger.debug("MonitorAIXLPARWorkload: channel connected/closed/eof: " + channel.isConnected() + "/" + channel.isClosed() + "/"
                                                + channel.isEOF());
				String output = slurpToEof(in);
				logger.debug("MonitorAIXLPARWorkload: channel connected/closed/eof: " + channel.isConnected() + "/" + channel.isClosed() + "/"
                                                + channel.isEOF());
				logger.debug("MonitorAIXLPARWorkload: output=" + output);
				if(output.equals("No results were found.")) {
					message += "Please check time on HMC if it's synchronized with the monitoring station.";
				}
				channel.disconnect();
				
				// Parse the output of lslparutil to find the frame's utilizations
				String[] outputArray = output.split("[\\r\\n]+");
				for(int i = 0; i < outputArray.length; i++) {
					logger.debug("MonitorAIXLPARWorkload: outputArray[" + i + "]=" + outputArray[i]);
				}
				
				String[] metrics = outputArray[0].split(",");
				for(int i = 0; i < metrics.length; i++) {
					logger.debug("MonitorAIXLPARWorkload: metrics[" + i + "]=" + metrics[i]);
				}				
				Double newTotalAvail = Double.parseDouble(metrics[0]);
				Double newTotalUtil = Double.parseDouble(metrics[1]);
				Double poolTotal = Double.parseDouble(metrics[2]);
				
				metrics = outputArray[1].split(",");
				for(int i = 0; i < metrics.length; i++) {
					logger.debug("MonitorAIXLPARWorkload: metrics[" + i + "]=" + metrics[i]);
				}
				Double oldTotalAvail = Double.parseDouble(metrics[0]);
				Double oldTotalUtil = Double.parseDouble(metrics[1]);
				
				logger.debug("MonitorAIXLPARWorkload: chassisUtilPCT=" + (newTotalUtil - oldTotalUtil) / (newTotalAvail - oldTotalAvail) * 100);
				addVariable("chassisUtilPCT", (newTotalUtil - oldTotalUtil) / (newTotalAvail - oldTotalAvail) * 100); 
				logger.debug("MonitorAIXLPARWorkload: chassisUtilUNITS=" + (newTotalUtil - oldTotalUtil) / (newTotalAvail - oldTotalAvail) * poolTotal);
				addVariable("chassisUtilUNITS", (newTotalUtil - oldTotalUtil) / (newTotalAvail - oldTotalAvail) * poolTotal); 
				
				
				
				
				// Send this command to the HMC to get the LPAR's utilizations
				sshCommand = "lslparutil -r lpar -m " + frameName + 
								" --startyear " + currentDateWithOffset.get(Calendar.YEAR) +
								" --startmonth " + currentMonthWithOffset +
								" --startday " + currentDateWithOffset.get(Calendar.DATE) +
								" --starthour " + currentDateWithOffset.get(Calendar.HOUR_OF_DAY) +
								" --startminute " + currentDateWithOffset.get(Calendar.MINUTE) +
								" --endmonth " + currentMonth +
								" --endday " + currentDate.get(Calendar.DATE) +
								" --endhour " + currentDate.get(Calendar.HOUR_OF_DAY) +
								" --endminute " + currentDate.get(Calendar.MINUTE) +
								" --filter \"event_types=sample\" -F lpar_name,curr_proc_units,entitled_cycles,capped_cycles,uncapped_cycles";
				logger.debug("MonitorAIXLPARWorkload: sending command: " + sshCommand);
				channel = sshSession.openChannel("exec");
				((ChannelExec)channel).setCommand(sshCommand);	
				logger.debug("MonitorAIXLPARWorkload: ((ChannelExec)channel).setCommand(sshCommand)");
				in = channel.getInputStream();
				logger.debug("MonitorAIXLPARWorkload: in = channel.getInputStream()");
				channel.connect(30000);
				logger.debug("MonitorAIXLPARWorkload: channel.connect(30000);");
				output = slurpToEof(in);
				logger.debug("MonitorAIXLPARWorkload:  output = slurpToEof(in); output=" + output);
				outputArray = output.split("[\\r\\n]+");
				for(int i = 0; i < outputArray.length; i++) {
					logger.debug("MonitorAIXLPARWorkload: outputArray[" + i + "]=" + outputArray[i]);
				}
				
				boolean finish = false;
				boolean alreadyAdded = false;
				ArrayList<Lpar> listOfLpars = new ArrayList<Lpar>();
				ArrayList<Lpar> lparResults = new ArrayList<Lpar>();

				// Go through all the output
				for (int i = 0; i < outputArray.length - 1; i++) {
					// breaks output into array
					metrics = outputArray[i].split(",");
					
					// Loop through LPARS that we've already read and check if the current line is for
					// an LPAR we have read before.  Do this to find the previous value.
					for(Lpar currentLpar : listOfLpars){
						// If the current output line is for an LPAR that we've already read AND
						// latestResult is false <-- we haven't found the next line yet
						// this means we are reading value from t0
						if ((currentLpar.getName().equals(metrics[0].replace(" ","_"))) && (currentLpar.getLatestResult() == false)) {
							alreadyAdded = true;
							Lpar tmpLpar = new Lpar(currentLpar.getName(), 
													currentLpar.getProcUnit(), 
													currentLpar.getEntitledCycles() - Double.parseDouble(metrics[2]), 
													currentLpar.getCappedCycles() - Double.parseDouble(metrics[3]), 
													currentLpar.getUncappedCycles() - Double.parseDouble(metrics[4]), true);
							lparResults.add(tmpLpar);
							currentLpar.setLatestResult(true);
							logger.debug("MonitorAIXLPARWorkload: found matching LPAR currentLpar=" + currentLpar.getName());
						}
					}
					if (alreadyAdded == false) {
						listOfLpars.add(new Lpar(metrics[0].replace(" ","_"),Double.parseDouble(metrics[1]),Double.parseDouble(metrics[2]),Double.parseDouble(metrics[3]),Double.parseDouble(metrics[4]),false));
						logger.debug("MonitorAIXLPARWorkload: adding to list of LPARs: " + metrics[0].replace(" ","_"));
					}
				}
				
				
				// Calculate various utilization metrics and add them to monitor variable
				Double usedPCT;				
				for(Lpar currentLpar : lparResults) {
					PluginMonitorVariable resultUsedPCT = new PluginMonitorVariable();
					resultUsedPCT.setName("usedPCT");
					resultUsedPCT.setObjectName(currentLpar.getName());
					if (currentLpar.getEntitledCycles() == 0) {
						usedPCT = 0.0;
					} else {	
						usedPCT = ((currentLpar.getCappedCycles() + currentLpar.getUncappedCycles()) / currentLpar.getEntitledCycles()) * 100;
					}
					resultUsedPCT.setValue(Double.toString(usedPCT));
					addVariable(resultUsedPCT);
					resultUsedPCT = null;
					
					PluginMonitorVariable resultUsedUNITS = new PluginMonitorVariable();
					resultUsedUNITS.setName("usedUNITS");
					resultUsedUNITS.setObjectName(currentLpar.getName());
					if (usedPCT == 0.0) {
						resultUsedUNITS.setValue("0.0");
					}
					else {
						resultUsedUNITS.setValue(Double.toString(currentLpar.getProcUnit() / 100 * usedPCT));
					}
					addVariable(resultUsedUNITS);
					logger.debug("MonitorAIXLPARWorkload: adding variable instance:" + currentLpar.getName() + " usedPCT=" +Double.toString(usedPCT) + " usedUNITS=" + Double.toString(currentLpar.getProcUnit() / 100 * usedPCT));
					resultUsedUNITS = null;
				}
	
				channel.disconnect();
				sshSession.disconnect();
					
				message += "Monitor ran successfully.";
				

			} catch (JSchException e) {

				message = "JSchException in monitor. " + e.getMessage();
				setState(MonitorState.CRIT);
			} catch (IOException ioe) {
				message = "IO Exception in monitor. " + ioe.getMessage();
				setState(MonitorState.CRIT);
			} catch (Exception e) {
				message = "General Exception in monitor. " + e.getMessage();
				setState(MonitorState.CRIT);
			} 
			setMessage(message);
		}
	}
	

}

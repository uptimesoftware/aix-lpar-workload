package com.uptimesoftware.uptime.erdc;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.uptimesoftware.uptime.base.KeyValuePair;
import com.uptimesoftware.uptime.base.display.graph.EntitySubtypeByErdcIdLoader;
import com.uptimesoftware.uptime.base.entity.EntityIdByErdcLoader;
import com.uptimesoftware.uptime.base.entity.EntitySubType;
import com.uptimesoftware.uptime.base.erdc.ErdcConfiguration;
import com.uptimesoftware.uptime.base.erdc.datasaving.LatestRetainedDataLoader;
import com.uptimesoftware.uptime.base.erdc.instance.ErdcInstance;
import com.uptimesoftware.uptime.base.erdc.instance.ErdcInstanceWithConfigurationOnlyLoader;
import com.uptimesoftware.uptime.base.erdc.instance.PerformanceErdcInstanceByEntityLoader;
import com.uptimesoftware.uptime.base.erdc.instance.parameters.ErdcParameter;
import com.uptimesoftware.uptime.base.erdc.instance.parameters.ErdcRequestParameter;
import com.uptimesoftware.uptime.base.erdc.instance.parameters.ErdcRetainedParameter;
import com.uptimesoftware.uptime.base.util.Parameters;
import com.uptimesoftware.uptime.base.util.Seconds;
import com.uptimesoftware.uptime.base.util.StackLogger;
import com.uptimesoftware.uptime.base.util.TimeInterval;
import com.uptimesoftware.uptime.database.Session;
import com.uptimesoftware.uptime.database.session.SessionManager;
import com.uptimesoftware.uptime.erdc.baseclass.MonitorWithMonitorVariables;
import com.uptimesoftware.uptime.erdc.custom.CustomOutputParser;
import com.uptimesoftware.uptime.erdc.custom.MonitorVariable;

import com.uptimesoftware.uptime.erdc.performance.AgentPortByEntityIdLoader;
import com.uptimesoftware.uptime.ranged.LatestRangedDataByInstanceIdLoader;
import com.uptimesoftware.uptime.scriptexecutor.ProcessBuilderShell;
import com.uptimesoftware.uptime.scriptexecutor.ScriptExecutor;

public class CustomServiceMonitorVariables extends MonitorWithMonitorVariables {
    protected String scriptName;
    protected String scriptArguments = "";
    private String scriptOutput;
    private Parameters parameters;
    private TimeInterval timeout;
    private static final Logger logger = LogManager.getLogger(CustomServiceMonitorVariables.class);

    @Override
    protected void monitor() {
            String errorString = "";
            try {

                    ScriptExecutor executor = setupExecutor(scriptName);

                    if (timeout != null) {
                            executor.setTimeout(timeout);
                    }

                    int retCode = executor.execute();

                    List<MonitorVariable> variables = parseOutput(executor);

                    for (MonitorVariable variable : variables) {
                            addVariable(variable);
                    }

                    errorString = executor.getStandardErr().toString();

                    determineStatusFromExitCode(retCode);

            } catch (Exception e) {
                    StackLogger.debug(this, e, "Failed to run script.");
                    scriptOutput = "";
                    setMessage("Failed to run script: " + e.getMessage());
                    setState(ErdcTransientState.CRIT);
            }

            if (errorString.length() > 0) {
                    setMessage(errorString);
            }
    }

    private ScriptExecutor setupExecutor(String scriptName) {
            ScriptExecutor executor = new ScriptExecutor();
            executor.setShell(new ProcessBuilderShell());
            executor.setScriptFile(new File(scriptName));
            addArgumentsToExecutor(executor);
            return executor;
    }

    private List<MonitorVariable> parseOutput(ScriptExecutor executor) {
            scriptOutput = executor.getStandardOut();
            scriptOutput = scriptOutput.replaceAll("(\r\n)", "\n");
            scriptOutput = scriptOutput.replaceAll("\r", "");

            return convertLinesToVariables(scriptOutput.split("\n"));
    }

    public void addArgumentsToExecutor(ScriptExecutor executor) {
            executor.addArgument(getHostname());
            String[] splitArgs = scriptArguments.split(" ");
            for (String argument : splitArgs) {
                    executor.addArgument(argument);
            }

            CustomMonitorEnvironmentVariables environementVariables = getEnvironementVariables();
            for (KeyValuePair<String, String> pair : environementVariables) {
                    executor.addEnvironmentVariable(pair.getKey(), pair.getValue());
            }
    }

    private CustomMonitorEnvironmentVariables getEnvironementVariables() {
            CustomMonitorEnvironmentVariables environmentVariables = new CustomMonitorEnvironmentVariables();
            environmentVariables.add(parameters);

            Session session = SessionManager.getSession();
            try {
                    addConfigurationEnvironmentVariables(environmentVariables, session);
                    addPastRetainedData(environmentVariables, session);
            } catch (Exception e) {
                    logger.debug("Error adding environment variables: " + e.getMessage());
            } finally {
                    session.close();
            }
            return environmentVariables;
    }

    private void addPastRetainedData(CustomMonitorEnvironmentVariables environmentVariables, Session session) {
            if (includeLastRun()) {
                    Collection<MonitorVariable> lastRunValues = getLastRunValues(session);
                    for (MonitorVariable monitorVariable : lastRunValues) {
                            environmentVariables.add(monitorVariable);
                    }
            }
    }

    //###############################################
    //# START OF EDITS
    //# Author: Joel Pereira
    //# Date: June 29, 2012
    //# Adding extra variables for AIX LPAR Workload plugin (HMC_NAME and PFRAME_NAME)
    //#
    private void addConfigurationEnvironmentVariables(CustomMonitorEnvironmentVariables environmentVariables, Session session) {
        // SYSTEM_TYPE
        String systemTypeString = getSystemTypeString(session);
        environmentVariables.add("SYSTEM_TYPE", systemTypeString);
        // AGENT_PORT
        Integer agentPort = getAgentPort(session);
        if (agentPort != null) {
                environmentVariables.add("AGENT_PORT", String.valueOf(agentPort));
        }

        try {
            // HMC_HOST
            String HmcName = getHMCHostName();
            if (HmcName != null) {
                environmentVariables.add("HMC_HOST", HmcName);
            }
            // PFRAME_NAME
            String PSeriesFrameName = getPSeriesFrameName();
            if (PSeriesFrameName != null) {
                environmentVariables.add("PFRAME_NAME", PSeriesFrameName);
            }
        } catch (Exception ex) {
            logger.error(ex.getMessage());
            setMessage(ex.getMessage());
            setState(ErdcTransientState.WARN);
        }
    }

    
    private String getHMCHostName() {
        Long erdcInstanceId = getInstanceId();

        EntityIdByErdcLoader loader = new EntityIdByErdcLoader();
        loader.setErdcId(erdcInstanceId);
        Long hmcEntityId = loader.execute();

        PerformanceErdcInstanceByEntityLoader ppgLoader = new PerformanceErdcInstanceByEntityLoader();
        ppgLoader.setEntityId(hmcEntityId);
        ErdcInstance ppg = ppgLoader.load();

        // get a specific variable in the ppg
        ErdcConfiguration ppgConfiguration = ppg.getConfiguration();
        ErdcRequestParameter hmc_hostname = ppgConfiguration.getRequestParameterByName("hmc-hostname");

        // go through the list of variables in the ppg
        /*List<ErdcRequestParameter> requestParameters = ppgConfiguration.getRequestParameters();
        for (ErdcRequestParameter erdcRequestParameter : requestParameters) {

        }*/
        return hmc_hostname.getValue();
    }
    private String getPSeriesFrameName() {
        Long erdcInstanceId = getInstanceId();

        EntityIdByErdcLoader loader = new EntityIdByErdcLoader();
        loader.setErdcId(erdcInstanceId);
        Long hmcEntityId = loader.execute();

        PerformanceErdcInstanceByEntityLoader ppgLoader = new PerformanceErdcInstanceByEntityLoader();
        ppgLoader.setEntityId(hmcEntityId);
        ErdcInstance ppg = ppgLoader.load();

        // get a specific variable in the ppg
        ErdcConfiguration ppgConfiguration = ppg.getConfiguration();
        ErdcRequestParameter managed_server = ppgConfiguration.getRequestParameterByName("managed-server");

        // go through the list of variables in the ppg
        /*List<ErdcRequestParameter> requestParameters = ppgConfiguration.getRequestParameters();
        for (ErdcRequestParameter erdcRequestParameter : requestParameters) {

        }*/
        return managed_server.getValue();
    }
    
    //#
    //# END OF EDITS
    //###############################################



    protected Integer getAgentPort(Session session) {
            AgentPortByEntityIdLoader loader = new AgentPortByEntityIdLoader();
            loader.setEntityId(getEntityFor(getInstanceId()));
            return loader.load(session);
    }

    private String getSystemTypeString(Session session) {
            EntitySubType systemSubType = getEntitySubType(session);

            return CustomScriptSytemTypeString.create(systemSubType);
    }

    protected EntitySubType getEntitySubType(Session session) {
            EntitySubtypeByErdcIdLoader loader = new EntitySubtypeByErdcIdLoader();
            loader.setConnectorFromSession(session);
            loader.setErdcId(getInstanceId());
            return loader.execute();
    }

    protected boolean includeLastRun() {
            return parameters.getBoolean("use-last-run");
    }

    private void determineStatusFromExitCode(int retCode) {
            if (retCode == 0) {
                    setMessage("Process returned with valid status");
            } else if (retCode == 1) {
                    setState(ErdcTransientState.WARN);
                    setMessage("Process returned with warning status");
            } else if (retCode == 2) {
                    setState(ErdcTransientState.CRIT);
                    setMessage("Process returned with critical status");
            } else {
                    setState(ErdcTransientState.UNKNOWN);
                    setMessage("Process returned with unknown status");
            }
            setMessage(getMessage() + (" - " + scriptOutput.replace("\n", "  ")));
    }

    @Override
    public void setParameters(Parameters params, Long instanceId) {
            super.setParameters(params, instanceId);
            this.parameters = params;

            timeout = parseTimeout(params);

            setScriptParameters(params);
    }

    private Seconds parseTimeout(Parameters params) {
            Seconds timeout = null;
            String timeoutString = params.get("timeout");
            if (timeoutString != null) {
                    timeout = new Seconds(Integer.parseInt(timeoutString));
            }
            return timeout;
    }

    protected void setScriptParameters(Parameters params) {
            scriptName = checkRequired("process");

            Object value = params.get("args");
            if (value != null) {
                    scriptArguments = (String) value;
                    // else default will be used
            }

            checkRequired("hostname");
    }

    private List<MonitorVariable> convertLinesToVariables(String[] variableValues) {
            List<MonitorVariable> variables = new ArrayList<MonitorVariable>();

            CustomOutputParser parser = new CustomOutputParser();

            for (int counter = 0; counter < variableValues.length; counter++) {
                    String line = variableValues[counter];
                    MonitorVariable variable = null;

                    if (!"".equals(line.trim())) {
                            variable = parser.parseLine(line);
                    }

                    if (variable != null) {
                            variables.add(variable);
                    }
            }
            return variables;
    }

    protected Collection<MonitorVariable> getLastRunValues(Session session) {
            Long instanceId = getInstanceId();

            Collection<MonitorVariable> lastRunVariables = new ArrayList<MonitorVariable>();
            Collection<MonitorVariable> lastRetainedVariables = getLatestRetainedVariables(session, instanceId);
            Collection<MonitorVariable> lastRangedVariables = getLatestRangedVariables(session, instanceId);

            lastRunVariables.addAll(lastRetainedVariables);
            lastRunVariables.addAll(lastRangedVariables);
            return lastRunVariables;
    }

    protected Collection<MonitorVariable> getLatestRetainedVariables(Session session, Long instanceId) {
            List<ErdcRetainedParameter> retainedParameters = getRetainedParameters(instanceId, session);
            return createMonitorVariables(instanceId, retainedParameters);
    }

    protected Collection<MonitorVariable> createMonitorVariables(Long instanceId, List<ErdcRetainedParameter> retainedParameters) {
            Collection<MonitorVariable> lastRunVariables = new ArrayList<MonitorVariable>();
            for (ErdcRetainedParameter retained : retainedParameters) {
                    MonitorVariable latestVariable = getLatestVariable(instanceId, retained);
                    if (latestVariable != null) {
                            lastRunVariables.add(latestVariable);
                    }
            }
            return lastRunVariables;
    }

    private Collection<MonitorVariable> getLatestRangedVariables(Session session, Long instanceId) {
            LatestRangedDataByInstanceIdLoader loader = new LatestRangedDataByInstanceIdLoader();
            loader.setErdcInstanceId(instanceId);
            return loader.execute(session);
    }

    private MonitorVariable getLatestVariable(Long instanceId, ErdcRetainedParameter retainedParameter) {
            LatestRetainedDataLoader latestLoader = new LatestRetainedDataLoader();
            ErdcParameter parameter = retainedParameter.getParameter();
            latestLoader.setErdcInstanceId(instanceId);
            latestLoader.setErdcParameter(parameter);
            return latestLoader.load(getSession());
    }

    private List<ErdcRetainedParameter> getRetainedParameters(Long instanceId, Session session) {
            ErdcInstanceWithConfigurationOnlyLoader loader = new ErdcInstanceWithConfigurationOnlyLoader();
            loader.setId(instanceId);
            ErdcInstance erdc = loader.execute(session);
            return erdc.getConfiguration().getRetainedParameters();
    }
}
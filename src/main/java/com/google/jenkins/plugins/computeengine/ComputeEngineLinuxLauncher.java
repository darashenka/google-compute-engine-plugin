package com.google.jenkins.plugins.computeengine;

import com.google.api.services.compute.model.*;
import com.google.jenkins.plugins.computeengine.client.ComputeClient;
import com.google.jenkins.plugins.computeengine.ssh.GoogleKeyPair;
import hudson.ProxyConfiguration;
import hudson.Util;
import hudson.model.TaskListener;

import java.io.IOException;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.HTTPProxyData;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.Session;
import hudson.remoting.Channel;
import jenkins.model.Jenkins;

import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ComputeEngineLinuxLauncher extends ComputeEngineComputerLauncher {
    private static final Logger LOGGER = Logger.getLogger(ComputeEngineLinuxLauncher.class.getName());

    public static final String SSH_USER = "jenkins";
    public static final String SSH_METADATA_KEY = "ssh-keys";
    public static final String SSH_PUB_KEY_PREFIX = SSH_USER + ":ssh-rsa ";
    public static final String SSH_PUB_KEY_SUFFIX = " " + SSH_USER;

    private static int bootstrapAuthTries = 30;
    private static int bootstrapAuthSleepMs = 15000;

    //TODO: make this configurable
    public static final Integer SSH_PORT = 22;
    public static final Integer SSH_TIMEOUT = 10000;

    protected void log(Level level, ComputeEngineComputer computer, TaskListener listener, String message) {
        ComputeEngineCloud cloud = computer.getCloud();
        if (cloud != null)
            cloud.log(LOGGER, level, listener, message);
    }

    protected void logException(ComputeEngineComputer computer, TaskListener listener, String message, Throwable exception) {
        ComputeEngineCloud cloud = computer.getCloud();
        if (cloud != null)
            cloud.log(LOGGER, Level.WARNING, listener, message, exception);
    }

    protected void logInfo(ComputeEngineComputer computer, TaskListener listener, String message) {
        log(Level.INFO, computer, listener, message);
    }

    protected void logWarning(ComputeEngineComputer computer, TaskListener listener, String message) {
        log(Level.WARNING, computer, listener, message);
    }

    public ComputeEngineLinuxLauncher(String cloudName, Operation insertOperation) {
        super(cloudName, insertOperation);
    }

    protected void launch(ComputeEngineComputer computer, TaskListener listener, Instance inst)
            throws IOException, InterruptedException {
        final Connection bootstrapConn;
        final Connection conn;
        Connection cleanupConn = null; // java's code path analysis for final
        // doesn't work that well.
        boolean successful = false;
        PrintStream logger = listener.getLogger();
        logInfo(computer, listener, "Launching instance: " + computer.getNode().getNodeName());
        try {
            GoogleKeyPair kp = setupSshKeys(computer);
            boolean isBootstrapped = bootstrap(kp, computer, listener);
            if (isBootstrapped) {
                // connect fresh as ROOT
                logInfo(computer, listener, "connect fresh as root");
                cleanupConn = connectToSsh(computer, listener);
                if (!cleanupConn.authenticateWithPublicKey(SSH_USER, kp.getPrivateKey().toCharArray(), "")) {
                    logWarning(computer, listener, "Authentication failed");
                    return; // failed to connect
                }
            } else {
                logWarning(computer, listener, "bootstrapresult failed");
                return;
            }
            conn = cleanupConn;

            SCPClient scp = conn.createSCPClient();
            String tmpDir = "/tmp";
            logInfo(computer, listener, "Copying slave.jar to: " + tmpDir);
            scp.put(Jenkins.getInstance().getJnlpJars("slave.jar").readFully(), "slave.jar", tmpDir);

            //TODO: allow jvmopt configuration
            String launchString = "java -jar " + tmpDir + "/slave.jar -slaveLog slavelog.txt -agentLog agentlog.txt";

            logInfo(computer, listener, "Launching Jenkins agent via plugin SSH: " + launchString);
            final Session sess = conn.openSession();
            sess.execCommand(launchString);
            computer.setChannel(sess.getStdout(), sess.getStdin(), logger, new Channel.Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    sess.close();
                    conn.close();
                }
            });
        } catch (Exception e) {
            logException(computer, listener, "Error getting exception", e);
        }
    }

    private GoogleKeyPair setupSshKeys(ComputeEngineComputer computer) throws Exception { //TODO: better exceptions
        //TODO: is it possible to get a null cloud or client?
        ComputeEngineCloud cloud = computer.getCloud();
        ComputeClient client = cloud.client;
        ComputeEngineInstance instance = computer.getNode();

        GoogleKeyPair kp = GoogleKeyPair.generate();
        List<Metadata.Items> items = new ArrayList<>();
        items.add(new Metadata.Items().setKey(SSH_METADATA_KEY).setValue(kp.getPublicKey()));
        client.appendInstanceMetadata(cloud.projectId, instance.zone, instance.getNodeName(), items);
        return kp;
    }

    private boolean bootstrap(GoogleKeyPair kp, ComputeEngineComputer computer, TaskListener listener) throws IOException,
            Exception { //TODO: better exceptions
        logInfo(computer, listener, "bootstrap");
        Connection bootstrapConn = null;
        try {
            int tries = bootstrapAuthTries;
            boolean isAuthenticated = false;
            logInfo(computer, listener, "Getting keypair...");
            logInfo(computer, listener, "Using autogenerated keypair");
            while (tries-- > 0) {
                logInfo(computer, listener, "Authenticating as " + SSH_USER);
                try {
                    bootstrapConn = connectToSsh(computer, listener);
                    isAuthenticated = bootstrapConn.authenticateWithPublicKey(SSH_USER, kp.getPrivateKey().toCharArray(), "");
                } catch (IOException e) {
                    logException(computer, listener, "Exception trying to authenticate", e);
                    bootstrapConn.close();
                }
                if (isAuthenticated) {
                    break;
                }
                logWarning(computer, listener, "Authentication failed. Trying again...");
                Thread.sleep(bootstrapAuthSleepMs);
            }
            if (!isAuthenticated) {
                logWarning(computer, listener, "Authentication failed");
                return false;
            }
        } finally {
            if (bootstrapConn != null) {
                bootstrapConn.close();
            }
        }
        return true;
    }

    private Connection connectToSsh(ComputeEngineComputer computer, TaskListener listener) throws Exception {
        final long timeout = computer.getNode().getLaunchTimeoutMillis();
        final long startTime = System.currentTimeMillis();
        while (true) {
            try {
                long waitTime = System.currentTimeMillis() - startTime;
                if (timeout > 0 && waitTime > timeout) {
                    //TODO: better exception
                    throw new Exception("Timed out after " + (waitTime / 1000)
                            + " seconds of waiting for ssh to become available. (maximum timeout configured is "
                            + (timeout / 1000) + ")");
                }
                Instance instance = computer.refreshInstance();

                String host = new String();

                // If host has a public address, use it
                NetworkInterface nic = instance.getNetworkInterfaces().get(0);
                if (nic.getAccessConfigs() != null) {
                    for (AccessConfig ac : nic.getAccessConfigs()) {
                        if (ac.getType().equals(InstanceConfiguration.NAT_TYPE)) {
                            host = ac.getNatIP();
                        }
                    }
                }
                if (host.isEmpty()) {
                    host = nic.getNetworkIP();
                }

                int port = SSH_PORT;
                logInfo(computer, listener, "Connecting to " + host + " on port " + port + ", with timeout " + SSH_TIMEOUT
                        + ".");
                Connection conn = new Connection(host, port);
                ProxyConfiguration proxyConfig = Jenkins.getInstance().proxy;
                Proxy proxy = proxyConfig == null ? Proxy.NO_PROXY : proxyConfig.createProxy(host);
                if (!proxy.equals(Proxy.NO_PROXY) && proxy.address() instanceof InetSocketAddress) {
                    InetSocketAddress address = (InetSocketAddress) proxy.address();
                    HTTPProxyData proxyData = null;
                    if (null != proxyConfig.getUserName()) {
                        proxyData = new HTTPProxyData(address.getHostName(), address.getPort(), proxyConfig.getUserName(), proxyConfig.getPassword());
                    } else {
                        proxyData = new HTTPProxyData(address.getHostName(), address.getPort());
                    }
                    conn.setProxyData(proxyData);
                    logInfo(computer, listener, "Using HTTP Proxy Configuration");
                }
                //TODO: verify host key
                conn.connect(new ServerHostKeyVerifier() {
                    public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey)
                            throws Exception {
                        return true;
                    }
                }, SSH_TIMEOUT, SSH_TIMEOUT);
                logInfo(computer, listener, "Connected via SSH.");
                return conn;
            } catch (IOException e) {
                // keep retrying until SSH comes up
                logInfo(computer, listener, "Failed to connect via ssh: " + e.getMessage());
                logInfo(computer, listener, "Waiting for SSH to come up. Sleeping 5.");
                Thread.sleep(5000);
            }
        }
    }
}
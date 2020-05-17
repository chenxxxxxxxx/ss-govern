package org.ss.govern.server.node.master;

import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ss.govern.server.config.GovernServerConfig;
import org.ss.govern.server.node.NodeInfo;
import org.ss.govern.server.node.NodeStatus;
import org.ss.govern.server.node.RemoteNodeManager;
import org.ss.govern.utils.ThreadUtils;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 集群节点间的通信管理组件
 * master和master  master和slave之间通信
 * 1、和其他master节点建立网络连接，避免出现重复的链接
 * 2、底层基于队列和线程，发送请求给其他节点，接收其他节点
 * 发送过来的请求放入接收队列
 *
 * @author wangsz
 * @create 2020-04-09
 **/
public class NetworkManager {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkManager.class);

    private final int DEFAULT_RETRIES = 3;
    private final int CONNECT_TIMEOUT = 5000;

    public static final long PROTOCOL_VERSION = -65536L;

    private RemoteNodeManager remoteNodeManager;

    private Map<Integer, NodeInfo> remoteNodes;

    /**
     * 等待重试发起连接的master列表
     */
    private List<NodeInfo> retryConnectOtherMasterNodes = new CopyOnWriteArrayList<>();

    /**
     * 其他远程master节点建立好的连接
     * key nodeId
     * value socket
     */
    private ConcurrentHashMap<Integer, Socket> remoteNodeSockets = new ConcurrentHashMap<>();

    private Map<Integer, LinkedBlockingQueue<ByteBuffer>> queueSendMap = new ConcurrentHashMap<>();

    private LinkedBlockingQueue<ByteBuffer> masterQueueRecv = new LinkedBlockingQueue<>();

    private LinkedBlockingQueue<ByteBuffer> slaveQueueRecv = new LinkedBlockingQueue<>();

    private NodeInfo self;

    public NetworkManager(RemoteNodeManager remoteNodeManager) {
        this.remoteNodeManager = remoteNodeManager;
        this.remoteNodes = remoteNodeManager.getRemoteMasterNodes();
        this.self = getSelf();
        new RetryConnectMasterNodeThread().start();
    }

    public void waitOtherMasterNodesConnect() {
        new MasterConnectionListener(this).start();
    }

    public void waitSlaveNodeConnect() {
        new SlaveConnectionListener(this).start();
    }

    public void connectOtherMasterNodes() {
        List<NodeInfo> beforeMasterNodes = getBeforeMasterNodes();
        if (CollectionUtils.isEmpty(beforeMasterNodes)) {
            return;
        }
        for (NodeInfo beforeMasterNode : beforeMasterNodes) {
            connectBeforeMasterNode(beforeMasterNode);
        }
    }

    /**
     * 等待大多数节点启动
     */
    public void waitMostNodesConnected() {
        //无需等待所有节点连接，只需要超过一半的节点建立成功即可开始选举
        int mostNodeNum = remoteNodes.size() + 1;
        while (NodeStatus.isRunning() && remoteNodeSockets.size() < mostNodeNum) {
            LOG.info("wait for other node connect....");
            ThreadUtils.sleep(2000);
        }
        LOG.info("all node connect successful.....`");
    }

    private boolean connectBeforeMasterNode(NodeInfo nodeInfo) {
        int retries = 0;
        String ip = nodeInfo.getIp();
        int port = nodeInfo.getMasterConnectPort();
        int nodeId = nodeInfo.getNodeId();
        LOG.info("try to connect master node :" + ip + ":" + port);
        while (NodeStatus.isRunning() && retries <= DEFAULT_RETRIES) {
            try {
                InetSocketAddress endpoint = new InetSocketAddress(ip, port);
                Socket socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(0);
                socket.connect(endpoint, CONNECT_TIMEOUT);
                LOG.info("successfully connected master node :" + ip + ":" + port);
                initiateConnection(socket, self.getNodeId());
                addSocket(nodeId, socket);
                startMasterSocketIOThreads(nodeId, socket);
                return true;
            } catch (IOException e) {
                LOG.error("connect with " + nodeInfo.getIp() + " fail");
                retries++;
                if (retries <= DEFAULT_RETRIES) {
                    LOG.info(String.format("connect with %s fail, retry to connect %s times",
                            nodeInfo.getIp(), retries));
                }
            }
        }
        if (!retryConnectOtherMasterNodes.contains(nodeInfo)) {
            retryConnectOtherMasterNodes.add(nodeInfo);
            LOG.error("retried connect master node :" + ip + ":" + port + "failed, " +
                    "and it into retry connect list");
        }
        return false;
    }

    public void initiateConnection(final Socket sock, final Integer sid) {
        DataOutputStream dout;
        try {
            BufferedOutputStream buf = new BufferedOutputStream(sock.getOutputStream());
            dout = new DataOutputStream(buf);

            // Sending id and challenge
            // represents protocol version (in other words - message type)
            GovernServerConfig serverConfig = GovernServerConfig.getInstance();
            dout.writeLong(PROTOCOL_VERSION);
            dout.writeInt(sid);
            dout.writeInt(serverConfig.getIsControllerCandidate() ? 1 : 0);
            dout.flush();
        } catch (IOException e) {
            LOG.warn("Ignoring exception reading or writing challenge: ", e);
            closeSocket(sock);
        }
    }

    public Integer readNodeId(Socket sock) {
        DataInputStream din = null;
        Integer remoteNodeId = null;
        try {
            din = new DataInputStream(
                    new BufferedInputStream(sock.getInputStream()));
            //预留
            Long protocolVersion = din.readLong();
            remoteNodeId = din.readInt();
        } catch (IOException e) {
            LOG.error("Exception handling connection, addr: {}, closing server connection",
                    sock.getRemoteSocketAddress());
            closeSocket(sock);
        }
        return remoteNodeId;
    }

    /**
     * @param sock
     * @return remoteNodeId
     */
    public Integer receiveConnection(final Socket sock) {
        DataInputStream din = null;
        Integer remoteNodeId = null;
        try {
            din = new DataInputStream(
                    new BufferedInputStream(sock.getInputStream()));
            //预留
            Long protocolVersion = din.readLong();
            remoteNodeId = din.readInt();
            boolean isControllerCandidate = din.readInt() == 1 ? true : false;
            remoteNodeManager.updateNodeIsControllerCandidate(remoteNodeId, isControllerCandidate);
            addSocket(remoteNodeId, sock);
            GovernServerConfig serverConfig = GovernServerConfig.getInstance();
            Integer sid = serverConfig.getNodeId();
            initiateConnection(sock, sid);
        } catch (IOException e) {
            LOG.error("Exception handling connection, addr: {}, closing server connection",
                    sock.getRemoteSocketAddress());
            closeSocket(sock);
        }
        return remoteNodeId;
    }

    /**
     * 获取id比自己小的节点信息列表
     *
     * @return
     */
    private List<NodeInfo> getBeforeMasterNodes() {
        Integer nodeId = GovernServerConfig.getInstance().getNodeId();
        List<NodeInfo> beforeMasterNode = new ArrayList<>();
        for (NodeInfo nodeInfo : remoteNodes.values()) {
            if (nodeInfo.getNodeId() < nodeId) {
                beforeMasterNode.add(nodeInfo);
            }
        }
        return beforeMasterNode;
    }

    /**
     * 缓存建立好的连接
     *
     * @param client
     */
    private void addSocket(Integer nodeId, Socket client) {
        InetSocketAddress remoteAddr = (InetSocketAddress) client.getRemoteSocketAddress();
        String remoteAddrHostName = remoteAddr.getHostName();
        if (nodeId == null) {
            //接收到了没有再配置文件里的其他节点的连接
            LOG.error("established connection is not in the right remote address " + remoteAddrHostName + " nodeId = " + nodeId);
            try {
                client.close();
            } catch (IOException e) {
                LOG.error("close connection in unknown remote address failed", e);
            }
        } else {
            LOG.info("receive client's node id is " + nodeId + ",and put it in cache[" + remoteNodeSockets.keys() + "]");
            remoteNodeSockets.put(nodeId, client);
        }
    }

    public void closeSocket(Socket sock) {
        if (sock == null) {
            return;
        }
        try {
            sock.close();
        } catch (IOException ie) {
            LOG.error("Exception while closing", ie);
        }
    }

    private NodeInfo getSelf() {
        Integer nodeId = GovernServerConfig.getInstance().getNodeId();
        NodeInfo self = remoteNodes.get(nodeId);
        if (self != null) {
            return self;
        }
        LOG.error(String.format("nodeId = %s addr config can not find", nodeId));
        NodeStatus nodeStatus = NodeStatus.getInstance();
        nodeStatus.setStatus(NodeStatus.FATAL);
        return null;
    }

    public void startMasterSocketIOThreads(Integer remoteNodeId, Socket socket) {
        LinkedBlockingQueue<ByteBuffer> masterQueueSend = new LinkedBlockingQueue<>();
        if(queueSendMap.putIfAbsent(remoteNodeId, masterQueueSend) != null) {
            throw new IllegalArgumentException("nodeId : " + remoteNodeId + " is already exist");
        }
        new MasterNetworkWriteThread(remoteNodeId, socket, masterQueueSend,this).start();
        new MasterNetworkReadThread(remoteNodeId, socket, masterQueueRecv,this).start();
    }

    public void startSlvaeSocketIOThreads(Integer remoteNodeId, Socket socket) {
        LinkedBlockingQueue<ByteBuffer> slaveQueueSend = new LinkedBlockingQueue<>();
        if(queueSendMap.putIfAbsent(remoteNodeId, slaveQueueSend) != null) {
            throw new IllegalArgumentException("nodeId : " + remoteNodeId + " is already exist");
        }
        new MasterNetworkWriteThread(remoteNodeId, socket, slaveQueueSend,this).start();
        new MasterNetworkReadThread(remoteNodeId, socket, slaveQueueRecv,this).start();
    }

    /**
     * 向指定远程节点发送信息
     */
    public Boolean sendMessage(Integer remoteNodeId, ByteBuffer request) {
        try {
            LinkedBlockingQueue<ByteBuffer> sendQueue = queueSendMap.get(remoteNodeId);
            sendQueue.put(request);
        } catch (InterruptedException e) {
            LOG.error("put request into sendQueue error, remoteNodeId = " + remoteNodeId, e);
            return false;
        }
        return true;
    }

    public void removeSendQueue(Integer nodeId) {
        this.queueSendMap.remove(nodeId);
    }

    public ByteBuffer takeSendMessage(Integer nodeId) throws InterruptedException {
        LinkedBlockingQueue<ByteBuffer> queue = queueSendMap.get(nodeId);
        if (queue == null) {
            throw new IllegalArgumentException("error nodeId, can not find sendQueue by this nodeId:" + nodeId);
        }
        return queue.take();
    }

    /**
     * 阻塞式获取消息
     *
     * @return
     */
    public ByteBuffer takeMasterRecvMessage() throws InterruptedException {
        return masterQueueRecv.take();
    }

    /**
     * 阻塞式获取消息
     *
     * @return
     */
    public ByteBuffer takeSlaveRecvMessage() throws InterruptedException {
        return slaveQueueRecv.take();
    }

    /**
     * 网络连接监听器
     *
     * @author wangsz
     * @create 2020-04-10
     **/
    class MasterConnectionListener extends AbstractConnectionListener {

        private final Logger LOG = LoggerFactory.getLogger(MasterConnectionListener.class);

        public MasterConnectionListener(NetworkManager networkManager) {
            super(networkManager);
            init();
        }

        private void init() {
            GovernServerConfig config = GovernServerConfig.getInstance();
            bindPort = self.getMasterConnectPort();
        }

        @Override
        protected void doAccept(Socket client) {
            Integer remoteNodeId = receiveConnection(client);
            if(remoteNodeId != null) {
                startMasterSocketIOThreads(remoteNodeId, client);
            }
        }
    }

    /**
     * slave节点网络连接监听器
     *
     * @author wangsz
     * @create 2020-04-10
     **/
    class SlaveConnectionListener extends AbstractConnectionListener {

        private final Logger LOG = LoggerFactory.getLogger(MasterConnectionListener.class);

        public SlaveConnectionListener(NetworkManager networkManager) {
            super(networkManager);
            init();
        }

        private void init() {
            bindPort = self.getSlaveConnectPort();
        }

        @Override
        protected void doAccept(Socket client) {
            Integer remoteNodeId = readNodeId(client);
            if(remoteNodeId != null) {
                addSocket(remoteNodeId, client);
                startSlvaeSocketIOThreads(remoteNodeId, client);
            }
        }
    }

    class RetryConnectMasterNodeThread extends Thread {
        private final Logger LOG = LoggerFactory.getLogger(RetryConnectMasterNodeThread.class);
        private final int RETRY_CONNECT_MASTER_NODE_INTERVAL = 5 * 60 * 1000;

        @Override
        public void run() {
            while (NodeStatus.isRunning()) {
                List<NodeInfo> retryConnectSuccessNodes = new ArrayList<>();
                for (NodeInfo nodeInfo : retryConnectOtherMasterNodes) {
                    if (connectBeforeMasterNode(nodeInfo)) {
                        retryConnectSuccessNodes.add(nodeInfo);
                    }
                }
                for (NodeInfo successNode : retryConnectSuccessNodes) {
                    retryConnectOtherMasterNodes.remove(successNode);
                }
                try {
                    Thread.sleep(RETRY_CONNECT_MASTER_NODE_INTERVAL);
                } catch (InterruptedException e) {
                    LOG.error("retryConnectMasterNodeThread Interrupted while sleeping. " +
                            "Ignoring exception", e);
                }
            }
        }
    }

}
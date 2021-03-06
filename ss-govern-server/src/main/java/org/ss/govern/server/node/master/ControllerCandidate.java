package org.ss.govern.server.node.master;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ss.govern.core.constants.MasterNodeRole;
import org.ss.govern.server.config.GovernServerConfig;
import org.ss.govern.server.node.MessageReceiver;
import org.ss.govern.server.node.NetworkManager;
import org.ss.govern.server.node.NodeManager;
import org.ss.govern.server.node.NodeStatus;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * controller候选人
 * @author wangsz
 * @create 2020-04-08
 **/
public class ControllerCandidate {

    private static final Logger LOG = LoggerFactory.getLogger(ControllerCandidate.class);

    private NetworkManager masterNetworkManager;

    private MessageReceiver messageReceiver;

    private NodeManager remoteNodeManager;

    private GovernServerConfig serverConfig;

    private int voteRound = 1;

    private Vote currentVote;

    private Integer selfId;

    public ControllerCandidate(NetworkManager masterNetworkManager,
                               NodeManager remoteNodeManager,
                               MessageReceiver messageReceiver) {
        this.masterNetworkManager = masterNetworkManager;
        this.remoteNodeManager = remoteNodeManager;
        this.messageReceiver = messageReceiver;
        this.serverConfig = GovernServerConfig.getInstance();
        this.selfId = serverConfig.getNodeId();
    }

    /**
     * 投票选举controller
     * @return
     */
    public MasterNodeRole voteForControllerElection() throws InterruptedException {
        this.currentVote = new Vote(selfId, selfId, voteRound);
        HashMap<Integer, Vote> recvSet = new HashMap<>();
        recvSet.put(selfId, currentVote);

        List<MasterNodePeer> otherControllerCandidates = remoteNodeManager.getOtherControllerCandidate();
        Integer controllerId;
        do {
            controllerId = startNextRoundVote(otherControllerCandidates, recvSet);
            if(controllerId != null) {
                break;
            }
        } while (NodeStatus.isRunning());
        return controllerId.equals(selfId) ? MasterNodeRole.CONTROLLER : MasterNodeRole.CANDIDATE;
    }

    private Integer startNextRoundVote(List<MasterNodePeer> otherControllerCandidates, HashMap<Integer, Vote> recvSet) throws InterruptedException {
        int candidateCount = (1 + otherControllerCandidates.size());
        int quorum = candidateCount / 2 + 1;
        Vote vote = this.currentVote;
        recvSet.put(vote.getVoterId(), vote);
        if(LOG.isDebugEnabled()) {
            LOG.debug("start "  + voteRound + " round vote");
        }
        for (MasterNodePeer node : otherControllerCandidates) {
            Integer remoteNodeId = node.getNodeId();
            masterNetworkManager.sendMessage(remoteNodeId, vote.toRequestByteBuffer());
        }
        while (NodeStatus.isRunning()) {
            Vote recvVote = messageReceiver.takeVote();
            if(recvVote.getVoterId() == null) {
                continue;
            }
            recvSet.put(recvVote.getVoterId(), recvVote);
            if(recvSet.size() >= quorum) {
                Integer controllerId = elect(recvSet, quorum);
                if(controllerId != null) {
                    // 清空队列,取出所有选票

                    return controllerId;
                }
            }
            //所有候选人的选票都收到，但是没有决定出controller
            if(candidateCount == recvSet.size()) {
                //下一轮投票前修改自己的选票
                Integer betterId = getBetterControllerId(recvSet.values());
                voteRound++;
                this.currentVote = new Vote(this.selfId, betterId, voteRound);
                recvSet.clear();
                break;
            }
        }
        return null;
    }

    private Integer elect(HashMap<Integer, Vote> recvSet, int quorum) {
        Map<Integer, Integer> voteCountMap = new HashMap<>();
        for (Vote vote : recvSet.values()) {
            Integer candidateId = vote.getCandidateId();
            Integer count = voteCountMap.get(candidateId);
            if(count == null) {
                count = 0;
            }
            voteCountMap.put(candidateId, ++count);
        }
        for (Map.Entry<Integer, Integer> voteCountEntry : voteCountMap.entrySet()) {
            if(voteCountEntry.getValue() >= quorum) {
                return voteCountEntry.getKey();
            }
        }
        return null;
    }

    /**
     * 获取下一轮的候选人节点id
     * */
    private Integer getBetterControllerId(Collection<Vote> votes) {
        Integer betterId = 0;
        for (Vote vote : votes) {
            Integer controllerId = vote.getVoterId();
            if(controllerId > betterId) {
                betterId = controllerId;
            }
        }
        return betterId;
    }
}

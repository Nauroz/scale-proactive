/*
 * ################################################################
 *
 * ProActive Parallel Suite(TM): The Java(TM) library for
 *    Parallel, Distributed, Multi-Core Computing for
 *    Enterprise Grids & Clouds
 *
 * Copyright (C) 1997-2010 INRIA/University of 
 * 				Nice-Sophia Antipolis/ActiveEon
 * Contact: proactive@ow2.org or contact@activeeon.com
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 3 of
 * the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307
 * USA
 *
 * If needed, contact us to obtain a release under GPL Version 2 
 * or a different license than the GPL.
 *
 *  Initial developer(s):               The ActiveEon Team
 *                        http://www.activeeon.com/
 *  Contributor(s):
 *
 * ################################################################
 * $$ACTIVEEON_INITIAL_DEV$$
 */
package org.objectweb.proactive.extra.messagerouting.router.processor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import org.objectweb.proactive.extra.messagerouting.PAMRConfig;
import org.objectweb.proactive.extra.messagerouting.exceptions.MalformedMessageException;
import org.objectweb.proactive.extra.messagerouting.protocol.AgentID;
import org.objectweb.proactive.extra.messagerouting.protocol.message.ErrorMessage;
import org.objectweb.proactive.extra.messagerouting.protocol.message.Message;
import org.objectweb.proactive.extra.messagerouting.protocol.message.RegistrationMessage;
import org.objectweb.proactive.extra.messagerouting.protocol.message.RegistrationReplyMessage;
import org.objectweb.proactive.extra.messagerouting.protocol.message.RegistrationRequestMessage;
import org.objectweb.proactive.extra.messagerouting.protocol.message.ErrorMessage.ErrorType;
import org.objectweb.proactive.extra.messagerouting.protocol.message.Message.MessageType;
import org.objectweb.proactive.extra.messagerouting.router.Attachment;
import org.objectweb.proactive.extra.messagerouting.router.Client;
import org.objectweb.proactive.extra.messagerouting.router.RouterImpl;


/** Asynchronous handler for {@link MessageType#DATA_REQUEST}
 * 
 * @since ProActive 4.1.0
 */
public class ProcessorRegistrationRequest extends Processor {

    final private Attachment attachment;

    public ProcessorRegistrationRequest(ByteBuffer messageAsByteBuffer, Attachment attachment,
            RouterImpl router) {
        super(messageAsByteBuffer, router);
        this.attachment = attachment;
    }

    public void process() throws MalformedMessageException {
        // Message.constructMessage guarantees that the cast is safe. If the message is not a RegistrationRequestMessage,
        // a @{link MalformedMessageException} will be thrown
        try {
            RegistrationRequestMessage message = (RegistrationRequestMessage) Message.constructMessage(
                    this.rawMessage.array(), 0);
            AgentID agentId = message.getAgentID();

            Client client = null;
            if (agentId == null) {
                client = connection(message);
            } else {
                client = reconnection(message);
            }

            if (client != null) {
                client.updateLastSeen();
            }
        } catch (MalformedMessageException e) {
            // try to see who sent it
            try {
                AgentID sender = RegistrationMessage.readAgentID(this.rawMessage.array(), 0);
                throw new MalformedMessageException(e, sender);
            } catch (MalformedMessageException e1) {
                // cannot get the sender
                throw new MalformedMessageException(e, true);
            }
        }
    }

    /* Generate and unique AgentID and send the registration reply
     * in best effort. If succeeded, add the new client to the router
     */
    private Client connection(RegistrationRequestMessage message) {
        long routerId = message.getRouterID();
        if (routerId != 0) {
            logger.warn("Invalid connection request. router ID must be 0. Remote endpoint is: " +
                attachment.getRemoteEndpointName());

            // Cannot contact the client yet, disconnect it !
            // Since we disconnect the client, we must free the resources
            this.attachment.dtor();
            return null;
        }

        AgentID agentId = AgentIdGenerator.getId();

        RegistrationMessage reply = new RegistrationReplyMessage(agentId, message.getMessageID(), this.router
                .getId(), getHeartbeatPeriod());

        Client client = new Client(attachment, agentId);
        boolean resp = this.sendReply(client, reply);
        if (resp) {
            this.router.addClient(client);
        }

        return client;
    }

    /* Check if the client is known. If not send an ERR_.
     * Otherwise, send the registration reply in best effort.
     * If succeeded, update the attachment in the client, and
     * flush the pending messages.
     */
    private Client reconnection(RegistrationRequestMessage message) {
        AgentID agentId = message.getAgentID();

        // Check that it is not an "old" client
        if (message.getRouterID() != this.router.getId()) {
            logger.warn("AgentId " + agentId +
                " asked to reconnect but the router IDs do not match. Remote endpoint is: " +
                attachment.getRemoteEndpointName());
            notifyInvalidAgent(message, agentId, ErrorType.ERR_INVALID_ROUTER_ID);
            return null;
        }

        // Check if the client is know
        Client client = router.getClient(agentId);
        if (client == null) {
            logger.warn("AgentId " + agentId +
                " asked to reconnect but is not known by this router. Remote endpoint is: " +
                attachment.getRemoteEndpointName());
            notifyInvalidAgent(message, agentId, ErrorType.ERR_INVALID_AGENT_ID);
        } else {
            // Acknowledge the registration
            client.setAttachment(attachment);
            RegistrationReplyMessage reply = new RegistrationReplyMessage(agentId, message.getMessageID(),
                this.router.getId(), getHeartbeatPeriod());

            boolean resp = this.sendReply(client, reply);
            if (resp) {
                client.sendPendingMessage();
            } else {
                logger.info("Failed to acknowledge the registration for " + agentId);
                // Drop the attachment
            }
        }

        return client;
    }

    private void notifyInvalidAgent(RegistrationRequestMessage message, AgentID agentId, ErrorType errorCode) {

        // Send an ERR_ message (best effort)
        ErrorMessage errMessage = new ErrorMessage(errorCode, agentId, agentId, message.getMessageID());

        try {
            attachment.send(ByteBuffer.wrap(errMessage.toByteArray()));
        } catch (IOException e) {
            logger.info("Failed to notify the client that invalid agent has been advertised");
        }

        // Since we disconnect the client, we must free the resources
        this.attachment.dtor();
    }

    /* Send the registration reply to the client (best effort)
     *
     * We don't want to cache the message on failure because if the tunnel
     * failed, the client will register again anyway.
     */
    private boolean sendReply(Client client, RegistrationMessage reply) {
        try {
            client.sendMessage(reply.toByteArray());
            return true;
        } catch (IOException e) {
            logger.info("Failed to send registration reply to " + reply.getAgentID() + ", IOException");
        }
        return false;
    }

    private int getHeartbeatPeriod() {
        return PAMRConfig.PA_PAMR_HEARTBEAT_TIMEOUT.getValue();
    }

    static abstract private class AgentIdGenerator {
        static final private AtomicLong generator = new AtomicLong(0);

        static public AgentID getId() {
            return new AgentID(generator.getAndIncrement());
        }
    }
}

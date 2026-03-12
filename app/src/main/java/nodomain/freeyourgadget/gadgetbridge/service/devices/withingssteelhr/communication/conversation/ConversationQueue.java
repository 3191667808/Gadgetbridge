/*  Copyright (C) 2023-2024 Frank Ertl

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;

import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.WithingsBaseDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.Message;

public class ConversationQueue implements ConversationObserver
{
    private static final Logger logger = LoggerFactory.getLogger(ConversationQueue.class);
    private final LinkedList<Conversation> queue = new LinkedList<>();
    private WithingsBaseDeviceSupport support;

    public ConversationQueue(WithingsBaseDeviceSupport support) {
        this.support = support;
    }

    @Override
    public void onConversationCompleted(short conversationType) {
        queue.remove(getConversation(conversationType));
        send();
    }

    public void clear() {
        queue.clear();
    }

    public void send() {
        logger.debug("Sending of queued messages has been requested.");
        if (!queue.isEmpty()) {
            Conversation nextInLine = queue.peek();
            if (nextInLine!= null) {
                logger.debug("Sending next queued message type={} (0x{})", nextInLine.getRequest().getType(), Integer.toHexString(nextInLine.getRequest().getType() & 0xffff));
                Message request = nextInLine.getRequest();
                support.sendToDevice(request);
            }
        } else {
            logger.debug("Queue is empty, nothing to send.");
        }
    }

    public void addConversation(Conversation conversation) {
        if (conversation == null) {
            return;
        }

        if (conversation.getRequest().needsResponse() || conversation.getRequest().needsEOT()) {
            logger.debug("addConversation: queuing type={} (0x{}) needsResponse={} needsEOT={}", conversation.getRequest().getType(), Integer.toHexString(conversation.getRequest().getType() & 0xffff), conversation.getRequest().needsResponse(), conversation.getRequest().needsEOT());
            queue.add(conversation);
            conversation.registerObserver(this);
        } else {
            logger.debug("addConversation: fire-and-forget type={} (0x{})", conversation.getRequest().getType(), Integer.toHexString(conversation.getRequest().getType() & 0xffff));
            support.sendToDevice(conversation.getRequest());
        }
    }

    public void processResponse(Message response) {
        logger.debug("processResponse: type={} (0x{}), queue size={}", response.getType(), Integer.toHexString(response.getType() & 0xffff), queue.size());
        for (Conversation c : queue) {
            if (c.getRequest() != null) {
                logger.debug("  queued conversation request type={} (0x{})", c.getRequest().getType(), Integer.toHexString(c.getRequest().getType() & 0xffff));
            }
        }
        Conversation conversation = getConversation(response.getType());
        if (conversation != null) {
            logger.debug("processResponse: matched conversation for type={}", response.getType());
            conversation.handleResponse(response);
        } else {
            logger.warn("processResponse: no conversation found for type={} (0x{}) -- message dropped!", response.getType(), Integer.toHexString(response.getType() & 0xffff));
        }
    }

    private Conversation getConversation(short requestType) {
        for (Conversation conversation : queue) {
            if (conversation.getRequest() != null && conversation.getRequest().getType() == requestType) {
                return conversation;
            }
        }

        return null;
    }
}

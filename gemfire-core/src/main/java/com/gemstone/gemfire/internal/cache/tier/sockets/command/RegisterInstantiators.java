/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
/**
 * 
 */
package com.gemstone.gemfire.internal.cache.tier.sockets.command;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.ClassPathLoader;
import com.gemstone.gemfire.internal.InternalDataSerializer;
import com.gemstone.gemfire.internal.InternalInstantiator;
import com.gemstone.gemfire.internal.cache.EnumListenerEvent;
import com.gemstone.gemfire.internal.cache.EventID;
import com.gemstone.gemfire.internal.cache.tier.Command;
import com.gemstone.gemfire.internal.cache.tier.sockets.BaseCommand;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheClientNotifier;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheServerHelper;
import com.gemstone.gemfire.internal.cache.tier.sockets.ClientInstantiatorMessage;
import com.gemstone.gemfire.internal.cache.tier.sockets.Message;
import com.gemstone.gemfire.internal.cache.tier.sockets.Part;
import com.gemstone.gemfire.internal.cache.tier.sockets.ServerConnection;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.logging.log4j.LocalizedMessage;


public class RegisterInstantiators extends BaseCommand {

  private final static RegisterInstantiators singleton = new RegisterInstantiators();
  
  public static Command getCommand() {
    return singleton;
  }

  private RegisterInstantiators() {
  }

  @Override
  public void cmdExecute(Message msg, ServerConnection servConn, long start)
      throws IOException, ClassNotFoundException {
    if (logger.isDebugEnabled()) {
      logger.debug("{}: Received register instantiator request ({} parts) from {}", servConn.getName(), msg.getNumberOfParts(), servConn.getSocketString());
    }
    int noOfParts = msg.getNumberOfParts();
    // Assert parts
    Assert.assertTrue((noOfParts - 1) % 3 == 0);
    // 3 parts per instantiator and one eventId part
    int noOfInstantiators = (noOfParts - 1) / 3;

    // retrieve eventID from the last Part
    ByteBuffer eventIdPartsBuffer = ByteBuffer.wrap(msg.getPart(noOfParts - 1)
        .getSerializedForm());
    long threadId = EventID
        .readEventIdPartsFromOptmizedByteArray(eventIdPartsBuffer);
    long sequenceId = EventID
        .readEventIdPartsFromOptmizedByteArray(eventIdPartsBuffer);
    EventID eventId = new EventID(servConn.getEventMemberIDByteArray(), threadId,
        sequenceId);

    byte[][] serializedInstantiators = new byte[noOfInstantiators * 3][];
    boolean caughtCNFE = false;
    Exception cnfe = null;
    try {
      for (int i = 0; i < noOfParts - 1; i = i + 3) {

        Part instantiatorPart = msg.getPart(i);
        serializedInstantiators[i] = instantiatorPart.getSerializedForm();
        String instantiatorClassName = (String)CacheServerHelper
            .deserialize(serializedInstantiators[i]);

        Part instantiatedPart = msg.getPart(i + 1);
        serializedInstantiators[i + 1] = instantiatedPart.getSerializedForm();
        String instantiatedClassName = (String)CacheServerHelper
            .deserialize(serializedInstantiators[i + 1]);

        Part idPart = msg.getPart(i + 2);
        serializedInstantiators[i + 2] = idPart.getSerializedForm();
        int id = idPart.getInt();

        Class instantiatorClass = null, instantiatedClass = null;
        try {
          instantiatorClass = InternalDataSerializer.getCachedClass(instantiatorClassName);
          instantiatedClass = InternalDataSerializer.getCachedClass(instantiatedClassName);
          InternalInstantiator.register(instantiatorClass, instantiatedClass,
            id, true, eventId, servConn.getProxyID());
        }
        catch (ClassNotFoundException e) {
          // If a ClassNotFoundException is caught, store it, but continue
          // processing other instantiators
          caughtCNFE = true;
          cnfe = e;
        }
      }
    }
    catch (Exception e) {
      logger.warn(LocalizedMessage.create(LocalizedStrings.RegisterInstantiators_BAD_CLIENT, new Object[] {servConn.getMembershipID(), e.getLocalizedMessage()}));
      writeException(msg, e, false, servConn);
      servConn.setAsTrue(RESPONDED);
    }
    
    // If a ClassNotFoundException was caught while processing the
    // instantiators, send it back to the client. Note: This only sends
    // the last CNFE.
    if (caughtCNFE) {
      writeException(msg, cnfe, false, servConn);
      servConn.setAsTrue(RESPONDED);

      //Send the instantiators on to other clients if we hit an error
      //due to a missing class, because they were not distributed
      //in InternalInstantiator.register. Otherwise they will have
      //been distributed if successfully registered.
      ClientInstantiatorMessage clientInstantiatorMessage = new ClientInstantiatorMessage(
          EnumListenerEvent.AFTER_REGISTER_INSTANTIATOR, serializedInstantiators,
          servConn.getProxyID(), eventId);

      // Notify other clients
      CacheClientNotifier.routeClientMessage(clientInstantiatorMessage);

    }
    
    // Send reply to client if necessary. If an exception occurs in the above
    // code, then the reply has already been sent.
    if (!servConn.getTransientFlag(RESPONDED)) {
      writeReply(msg, servConn);
    }

    if (logger.isDebugEnabled()) {
      logger.debug("Registered instantiators for MembershipId = {}", servConn.getMembershipID());
    }
  }

}

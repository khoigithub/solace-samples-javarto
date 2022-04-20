/**
 * Copyright 2004-2021 Solace Corporation. All rights reserved.
 *
 */
package com.solace.samples.javarto.features;

import java.nio.ByteBuffer;
import java.util.logging.Level;

import com.solacesystems.solclientj.core.SolEnum;
import com.solacesystems.solclientj.core.Solclient;
import com.solacesystems.solclientj.core.SolclientException;
import com.solacesystems.solclientj.core.event.MessageCallback;
import com.solacesystems.solclientj.core.event.SessionEventCallback;
import com.solacesystems.solclientj.core.handle.ContextHandle;
import com.solacesystems.solclientj.core.handle.Handle;
import com.solacesystems.solclientj.core.handle.MessageHandle;
import com.solacesystems.solclientj.core.handle.SessionHandle;
import com.solacesystems.solclientj.core.resource.Destination;
import com.solacesystems.solclientj.core.resource.Topic;

/**
 * RRDirectReplier.java
 * 
 * This sample shows how to implement a Requester for direct Request-Reply
 * messaging, where
 * 
 * <dl>
 * <dt>RRDirectRequester
 * <dd>A message Endpoint that sends a request message and waits to receive a
 * reply message as a response.
 * <dt>RRDirectReplier
 * <dd>A message Endpoint that waits to receive a request message and responses
 * to it by sending a reply message.
 * </dl>
 * 
 * <pre>
 *  |-------------------|  ---RequestTopic --> |------------------|
 *  | RRDirectRequester |                      | RRDirectReplier  |
 *  |-------------------|  <--ReplyToTopic---- |------------------|
 * </pre>
 * 
 * <strong>This sample illustrates the ease of use of concepts, and may not be
 * GC-free.<br>
 * See Perf* samples for GC-free examples. </strong>
 *  
 */
public class RRDirectReplier extends AbstractSample {

	private SessionHandle sessionHandle = Solclient.Allocator
			.newSessionHandle();

	private ContextHandle contextHandle = Solclient.Allocator
			.newContextHandle();

	private static ByteBuffer rxContent = ByteBuffer.allocateDirect(200);
	private static ByteBuffer txContent = ByteBuffer.allocateDirect(200);

	private static MessageHandle txMessageHandle = Solclient.Allocator
			.newMessageHandle();

	private static boolean quit = false;

	@Override
	protected void printUsage(boolean secureSession) {
		String usage = ArgumentsParser.getCommonUsage(secureSession);
		usage += "This sample:\n";
		usage += "\t[-t topic]\t Topic, default:" + SampleUtils.SAMPLE_TOPIC
				+ "\n";
		usage += "\t[-n number]\t Number of request messages to expect, default: 5\n";
		System.out.println(usage);
		finish(1);
	}

	/**
	 * This is the main method of the sample
	 */
	@Override
	protected void run(String[] args, SessionConfiguration config,Level logLevel)
			throws SolclientException {

		// Determine a destinationName (topic), default to
		// SampleUtils.SAMPLE_TOPIC
		String destinationName = config.getArgBag().get("-t");
		if (destinationName == null) {
			destinationName = SampleUtils.SAMPLE_TOPIC;
		}

		int numberOfRequestMessages = 5;

		String strCount = config.getArgBag().get("-n");
		if (strCount != null) {
			try {
				numberOfRequestMessages = Integer.parseInt(strCount);
			} catch (NumberFormatException e) {
				printUsage(config instanceof SecureSessionConfiguration);
			}
		}

		// Init
		print(" Initializing the Java RTO Messaging API...");
		int rc = Solclient.init(new String[0]);
		assertReturnCode("Solclient.init()", rc, SolEnum.ReturnCode.OK);

		// Set a log level (not necessary as there is a default)
		Solclient.setLogLevel(logLevel);
		
		// Context
		print(" Creating a context ...");
		rc = Solclient.createContextForHandle(contextHandle, new String[0]);
		assertReturnCode("Solclient.createContext()", rc, SolEnum.ReturnCode.OK);

		/* Create a Session */
		print(" Create a Session.");

		int spareRoom = 10;
		String[] sessionProps = getSessionProps(config, spareRoom);
		int sessionPropsIndex = sessionProps.length - spareRoom;

		/*
		 * Note: Reapplying subscriptions allows Sessions to reconnect after
		 * failure and have all their subscriptions automatically restored. For
		 * Sessions with many subscriptions this can increase the amount of time
		 * required for a successful reconnect.
		 */
		sessionProps[sessionPropsIndex++] = SessionHandle.PROPERTIES.REAPPLY_SUBSCRIPTIONS;
		sessionProps[sessionPropsIndex++] = SolEnum.BooleanValue.ENABLE;
		/*
		 * Note: Including meta data fields such as sender timestamp, sender ID,
		 * and sequence number will reduce the maximum attainable throughput as
		 * significant extra encoding/decoding is required. This is true whether
		 * the fields are autogenerated or manually added.
		 */
		sessionProps[sessionPropsIndex++] = SessionHandle.PROPERTIES.GENERATE_SEND_TIMESTAMPS;
		sessionProps[sessionPropsIndex++] = SolEnum.BooleanValue.ENABLE;
		sessionProps[sessionPropsIndex++] = SessionHandle.PROPERTIES.GENERATE_SENDER_ID;
		sessionProps[sessionPropsIndex++] = SolEnum.BooleanValue.ENABLE;
		sessionProps[sessionPropsIndex++] = SessionHandle.PROPERTIES.GENERATE_SEQUENCE_NUMBER;
		sessionProps[sessionPropsIndex++] = SolEnum.BooleanValue.ENABLE;

		/*
		 * The certificate validation property is ignored on non-SSL sessions.
		 * For simple demo applications, disable it on SSL sesssions (host
		 * string begins with tcps:) so a local trusted root and certificate
		 * store is not required. See the API users guide for documentation on
		 * how to setup a trusted root so the servers certificate returned on
		 * the secure connection can be verified if this is desired.
		 */
		sessionProps[sessionPropsIndex++] = SessionHandle.PROPERTIES.SSL_VALIDATE_CERTIFICATE;
		sessionProps[sessionPropsIndex++] = SolEnum.BooleanValue.DISABLE;

		SessionEventCallback sessionEventCallback = getDefaultSessionEventCallback();
		ReplierMessageReceivedCallback replierMessageReceivedCallback = new ReplierMessageReceivedCallback(
				numberOfRequestMessages, destinationName);

		/* Create the Session. */
		rc = contextHandle.createSessionForHandle(sessionHandle, sessionProps,
				replierMessageReceivedCallback, sessionEventCallback);
		assertReturnCode("contextHandle.createSession() - session", rc,
				SolEnum.ReturnCode.OK);

		/* Connect the Session. */
		print(" Connecting session ...");
		rc = sessionHandle.connect();
		assertReturnCode("sessionHandle.connect()", rc, SolEnum.ReturnCode.OK);

		/*************************************************************************
		 * Subscribe to the request topic
		 *************************************************************************/
		Topic topic = Solclient.Allocator.newTopic(destinationName);
		rc = sessionHandle.subscribe(topic,
				SolEnum.SubscribeFlags.WAIT_FOR_CONFIRM,0);
		assertReturnCode("sessionHandle.subscribe() to topic "
				+ destinationName, rc, SolEnum.ReturnCode.OK);

		/*************************************************************************
		 * Just wait until interrupted...
		 *************************************************************************/
		// Register a shutdown hook
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				quit = true;
			}
		});

		// Do some waiting, the registered callback withh handle messages with
		// replies, it will quit when done
		while (!quit) {
			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		print("Quitting time");

		print("Run() DONE");
	}

	/**
	 * Invoked when the sample finishes
	 */
	@Override
	protected void finish(int status) {

		/*************************************************************************
		 * Cleanup
		 *************************************************************************/

		finish_DestroyHandle(txMessageHandle, "messageHandle");

		finish_Disconnect(sessionHandle);

		finish_DestroyHandle(sessionHandle, "sessionHandle");

		finish_DestroyHandle(contextHandle, "contextHandle");

		finish_Solclient();
	}

	public static class ReplierMessageReceivedCallback implements
			MessageCallback {

		private int messageCount = 0;

		int expectedMax;

		public ReplierMessageReceivedCallback(int max, String destination) {
			expectedMax = max;
		}

		@Override
		public void onMessage(Handle handle) {
			try {

				SessionHandle sessionHandle = (SessionHandle) handle;
				MessageHandle rxMessage = sessionHandle.getRxMessage();

				rxContent.clear();
				// Get the binary attachment from the received message
				rxMessage.getBinaryAttachment(rxContent);
				rxContent.flip();

				int requestInt = rxContent.getInt();
				int expectedRequestInt = messageCount;

				print("-> RRDirectReplier -> Received request [" + requestInt
						+ "]");

				if (requestInt != expectedRequestInt) {
					throw new IllegalStateException(String.format(
							"[%d] was expected, got this request instead [%d]",
							expectedRequestInt, requestInt));
				}
				messageCount++;

				if (!txMessageHandle.isBound()) {
					// Allocate the message
					int rc = Solclient.createMessageForHandle(txMessageHandle);
					assertReturnCode("Solclient.createMessageForHandle()", rc,
							SolEnum.ReturnCode.OK);
				}

				// Reuse the same rxMessage handle to reply
				Destination destination = rxMessage.getReplyTo();

				txMessageHandle.setDestination(destination);

				// The Use the tx buffer
				txContent.clear();
				txContent.putInt(requestInt);
				txContent.flip();

				txMessageHandle.setBinaryAttachment(txContent);

				print("Sending reply [" + requestInt + "] to ["
						+ destination.getName() + "]");

				// Response
				int rc = sessionHandle.sendReply(rxMessage, txMessageHandle);
				assertReturnCode("sessionHandle.sendReply", rc,
						SolEnum.ReturnCode.OK);

			} catch (IllegalStateException ise) {
				quit = true;
				throw ise;
			} catch (SolclientException sce) {
				quit = true;
				throw sce;
			} finally {
				if (!quit && (messageCount >= expectedMax))
					quit = true;
			}

		}

	}

/**
     * Boilerplate, calls {@link #run(String[])
     * @param args
     */
	public static void main(String[] args) {
		RRDirectReplier sample = new RRDirectReplier();
		sample.run(args);
	}

}
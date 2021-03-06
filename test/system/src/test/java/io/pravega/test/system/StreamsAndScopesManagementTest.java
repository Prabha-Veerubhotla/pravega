/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.test.system;

import io.pravega.client.ClientConfig;
import io.pravega.client.ClientFactory;
import io.pravega.client.admin.StreamManager;
import io.pravega.client.stream.EventStreamWriter;
import io.pravega.client.stream.EventWriterConfig;
import io.pravega.client.stream.ScalingPolicy;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.client.stream.impl.Controller;
import io.pravega.client.stream.impl.ControllerImpl;
import io.pravega.client.stream.impl.ControllerImplConfig;
import io.pravega.client.stream.impl.JavaSerializer;
import io.pravega.common.concurrent.ExecutorServiceHelpers;
import io.pravega.test.system.framework.Environment;
import io.pravega.test.system.framework.SystemTestRunner;
import io.pravega.test.system.framework.Utils;
import io.pravega.test.system.framework.services.Service;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import mesosphere.marathon.client.MarathonException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import static io.pravega.test.common.AssertExtensions.assertThrows;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Slf4j
@RunWith(SystemTestRunner.class)
public class StreamsAndScopesManagementTest {

    private static final int NUM_SCOPES = 5;
    private static final int NUM_STREAMS = 20;
    private static final int NUM_EVENTS = 100;
    // Until the issue below is solved, TEST_ITERATIONS cannot be > 1.
    // TODO: Re-creation of Streams cannot be tested (Issue https://github.com/pravega/pravega/issues/2641).
    private static final int TEST_ITERATIONS = 1;
    @Rule
    public Timeout globalTimeout = Timeout.seconds(12 * 60);

    private final ScheduledExecutorService executor = ExecutorServiceHelpers.newScheduledThreadPool(4,
            "StreamsAndScopesManagementTest-controller");

    private URI controllerURI = null;
    private StreamManager streamManager = null;
    private Controller controller;
    private Map<String, List<Long>> controllerPerfStats = new HashMap<>();

    /**
     * This is used to setup the services required by the system test framework.
     *
     * @throws MarathonException When error in setup.
     */
    @Environment
    public static void initialize() throws MarathonException {

        // 1. Check if zk is running, if not start it.
        Service zkService = Utils.createZookeeperService();
        if (!zkService.isRunning()) {
            zkService.start(true);
        }

        List<URI> zkUris = zkService.getServiceDetails();
        log.debug("Zookeeper service details: {}", zkUris);
        // Get the zk ip details and pass it to bk, host, controller.
        URI zkUri = zkUris.get(0);

        // 2. Check if bk is running, otherwise start, get the zk ip.
        Service bkService = Utils.createBookkeeperService(zkUri);
        if (!bkService.isRunning()) {
            bkService.start(true);
        }

        List<URI> bkUris = bkService.getServiceDetails();
        log.debug("Bookkeeper service details: {}", bkUris);

        // 3. Start controller.
        Service conService = Utils.createPravegaControllerService(zkUri);
        if (!conService.isRunning()) {
            conService.start(true);
        }

        List<URI> conUris = conService.getServiceDetails();
        log.debug("Pravega controller service details: {}", conUris);

        // 4.Start segmentstore.
        Service segService = Utils.createPravegaSegmentStoreService(zkUri, conUris.get(0));
        if (!segService.isRunning()) {
            segService.start(true);
        }

        List<URI> segUris = segService.getServiceDetails();
        log.debug("Pravega segmentstore service details: {}", segUris);
    }

    @Before
    public void setup() {
        Service conService = Utils.createPravegaControllerService(null);
        List<URI> ctlURIs = conService.getServiceDetails();
        controllerURI = ctlURIs.get(0);
        streamManager = StreamManager.create(controllerURI);
        controller = new ControllerImpl(ControllerImplConfig.builder()
                                                            .clientConfig(ClientConfig.builder().controllerURI(controllerURI).build())
                                                            .maxBackoffMillis(5000).build(), executor);

        // Performance inspection.
        controllerPerfStats.put("createScopeMs", new ArrayList<>());
        controllerPerfStats.put("createStreamMs", new ArrayList<>());
        controllerPerfStats.put("sealStreamMs", new ArrayList<>());
        controllerPerfStats.put("deleteStreamMs", new ArrayList<>());
        controllerPerfStats.put("deleteScopeMs", new ArrayList<>());
        controllerPerfStats.put("updateStreamMs", new ArrayList<>());
    }

    @After
    public void tearDown() {
        streamManager.close();
        ExecutorServiceHelpers.shutdown(executor);
    }

    /**
     * This test executes a series of metadata operations on streams and scopes to verify their correct behavior. This
     * includes the creation and deletion of multiple scopes both in correct and incorrect situations. Moreover, for
     * each scope, the test creates a range of streams and tries to create, update, seal and delete them in correct and
     * incorrect situations. The test also performs metadata operation on empty and non-empty streams.
     */
    @Test
    public void testStreamsAndScopesManagement() {
        // Perform management tests with Streams and Scopes.
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            log.info("Stream and scope management test in iteration {}.", i);
            testStreamScopeManagementIteration();
        }

        // Provide some performance information of Stream/Scope metadata operations.
        for (String perfKey : controllerPerfStats.keySet()) {
            log.info("Performance of {}: {}", perfKey, controllerPerfStats.get(perfKey).stream().mapToLong(x -> x).summaryStatistics());
        }

        log.debug("Scope and Stream management test passed.");
    }

    // Start region utils

    private void testStreamScopeManagementIteration() {
        for (int i = 0; i < NUM_SCOPES; i++) {
            final String scope = "testStreamsAndScopesManagement" + String.valueOf(i);
            testCreateScope(scope);
            testCreateSealAndDeleteStreams(scope);
            testDeleteScope(scope);
        }
    }

    private void testCreateScope(String scope) {
        assertFalse(streamManager.deleteScope(scope));
        long iniTime = System.nanoTime();
        assertTrue("Creating scope", streamManager.createScope(scope));
        controllerPerfStats.get("createScopeMs").add(timeDiffInMs(iniTime));
    }

    private void testDeleteScope(String scope) {
        assertFalse(streamManager.createScope(scope));
        long iniTime = System.nanoTime();
        assertTrue("Deleting scope", streamManager.deleteScope(scope));
        controllerPerfStats.get("deleteScopeMs").add(timeDiffInMs(iniTime));
    }

    private void testCreateSealAndDeleteStreams(String scope) {
        for (int j = 1; j <= NUM_STREAMS; j++) {
            final String stream = String.valueOf(j);
            StreamConfiguration config = StreamConfiguration.builder().scalingPolicy(ScalingPolicy.fixed(j)).build();

            // Create Stream with nonexistent scope, which should not be successful.
            log.info("Creating a stream in a deliberately nonexistent scope nonexistentScope/{}.", stream);
            assertThrows(RuntimeException.class, () -> streamManager.createStream("nonexistentScope", stream,
                    StreamConfiguration.builder().build()));
            long iniTime = System.nanoTime();
            log.info("Creating stream {}/{}.", scope, stream);
            assertTrue("Creating stream", streamManager.createStream(scope, stream, config));
            controllerPerfStats.get("createStreamMs").add(timeDiffInMs(iniTime));

            // Update the configuration of the stream by doubling the number of segments.
            config = StreamConfiguration.builder().scalingPolicy(ScalingPolicy.fixed(j * 2)).build();
            iniTime = System.nanoTime();
            assertTrue(streamManager.updateStream(scope, stream, config));
            controllerPerfStats.get("updateStreamMs").add(timeDiffInMs(iniTime));

            // Perform tests on empty and non-empty streams.
            if (j % 2 == 0) {
                log.info("Writing events in stream {}/{}.", scope, stream);
                @Cleanup
                ClientFactory clientFactory = ClientFactory.withScope(scope, controllerURI);
                writeEvents(clientFactory, stream, NUM_EVENTS);
            }

            // Update the configuration of the stream.
            config = StreamConfiguration.builder().scalingPolicy(ScalingPolicy.fixed(j * 2)).build();
            assertTrue(streamManager.updateStream(scope, stream, config));

            // Attempting to delete non-empty scope and non-sealed stream.
            assertThrows(RuntimeException.class, () -> streamManager.deleteScope(scope));
            assertThrows(RuntimeException.class, () -> streamManager.deleteStream(scope, stream));

            // Seal and delete stream.
            log.info("Attempting to seal and delete stream {}/{}.", scope, stream);
            iniTime = System.nanoTime();
            assertTrue(streamManager.sealStream(scope, stream));
            controllerPerfStats.get("sealStreamMs").add(timeDiffInMs(iniTime));
            iniTime = System.nanoTime();
            assertTrue(streamManager.deleteStream(scope, stream));
            controllerPerfStats.get("deleteStreamMs").add(timeDiffInMs(iniTime));

            // Seal and delete already sealed/deleted streams.
            log.info("Sealing and deleting an already deleted stream {}/{}.", scope, stream);
            assertThrows(RuntimeException.class, () -> streamManager.sealStream(scope, stream));
            assertFalse(streamManager.deleteStream(scope, stream));
        }
    }

    private void writeEvents(ClientFactory clientFactory, String streamName, int totalEvents) {
        @Cleanup
        EventStreamWriter<String> writer = clientFactory.createEventWriter(streamName, new JavaSerializer<>(),
                EventWriterConfig.builder().build());
        for (int i = 0; i < totalEvents; i++) {
            writer.writeEvent(String.valueOf(i)).join();
            log.debug("Writing event: {} to stream {}", i, streamName);
        }
    }

    private long timeDiffInMs(long iniTime) {
        return (System.nanoTime() - iniTime) / 1000000;
    }

    // End region utils
}

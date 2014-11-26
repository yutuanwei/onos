/*
 * Copyright 2014 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onlab.onos.provider.lldp.impl;


import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onlab.onos.cluster.NodeId;
import org.onlab.onos.cluster.RoleInfo;
import org.onlab.onos.mastership.MastershipListener;
import org.onlab.onos.mastership.MastershipService;
import org.onlab.onos.net.ConnectPoint;
import org.onlab.onos.net.DefaultDevice;
import org.onlab.onos.net.DefaultPort;
import org.onlab.onos.net.Device;
import org.onlab.onos.net.DeviceId;
import org.onlab.onos.net.MastershipRole;
import org.onlab.onos.net.Port;
import org.onlab.onos.net.PortNumber;
import org.onlab.onos.net.device.DeviceEvent;
import org.onlab.onos.net.device.DeviceListener;
import org.onlab.onos.net.device.DeviceServiceAdapter;
import org.onlab.onos.net.flow.TrafficTreatment;
import org.onlab.onos.net.link.LinkDescription;
import org.onlab.onos.net.link.LinkProvider;
import org.onlab.onos.net.link.LinkProviderRegistry;
import org.onlab.onos.net.link.LinkProviderService;
import org.onlab.onos.net.packet.DefaultInboundPacket;
import org.onlab.onos.net.packet.InboundPacket;
import org.onlab.onos.net.packet.OutboundPacket;
import org.onlab.onos.net.packet.PacketContext;
import org.onlab.onos.net.packet.PacketProcessor;
import org.onlab.onos.net.packet.PacketService;
import org.onlab.onos.net.provider.AbstractProviderService;
import org.onlab.onos.net.provider.ProviderId;
import org.onlab.packet.ChassisId;
import org.onlab.packet.Ethernet;
import org.onlab.packet.ONOSLLDP;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class LLDPLinkProviderTest {

    private static final DeviceId DID1 = DeviceId.deviceId("of:0000000000000001");
    private static final DeviceId DID2 = DeviceId.deviceId("of:0000000000000002");

    private static Port pd1;
    private static Port pd2;
    private static Port pd3;
    private static Port pd4;

    private final LLDPLinkProvider provider = new LLDPLinkProvider();
    private final TestLinkRegistry linkService = new TestLinkRegistry();
    private final TestPacketService packetService = new TestPacketService();
    private final TestDeviceService deviceService = new TestDeviceService();
    private final TestMasterShipService masterService = new TestMasterShipService();

    private TestLinkProviderService providerService;

    private PacketProcessor testProcessor;
    private DeviceListener deviceListener;

    @Before
    public void setUp() {

        provider.deviceService = deviceService;
        provider.packetSevice = packetService;
        provider.providerRegistry = linkService;
        provider.masterService = masterService;


        provider.activate();
    }

    @Test
    public void basics() {
        assertNotNull("registration expected", providerService);
        assertEquals("incorrect provider", provider, providerService.provider());
    }

    @Test
    public void switchAdd() {
        DeviceEvent de = deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID1);
        deviceListener.event(de);

        assertFalse("Device not added", provider.discoverers.isEmpty());
    }

    @Test
    public void switchRemove() {
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID1));
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_REMOVED, DID1));

        assertTrue("Discoverer is not gone", provider.discoverers.get(DID1).isStopped());
        assertTrue("Device is not gone.", vanishedDpid(DID1));
    }

    @Test
    public void portUp() {
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID1));
        deviceListener.event(portEvent(DeviceEvent.Type.PORT_ADDED, DID1, port(DID1, 3, true)));

        assertTrue("Port not added to discoverer",
                   provider.discoverers.get(DID1).containsPort((long) 3));
    }

    @Test
    public void portDown() {

        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID1));
        deviceListener.event(portEvent(DeviceEvent.Type.PORT_ADDED, DID1, port(DID1, 1, false)));



        assertFalse("Port added to discoverer",
                    provider.discoverers.get(DID1).containsPort((long) 1));
        assertTrue("Port is not gone.", vanishedPort((long) 1));
    }

    @Test
    public void portUnknown() {
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID1));
        deviceListener.event(portEvent(DeviceEvent.Type.PORT_ADDED, DID2, port(DID2, 1, false)));


        assertNull("DeviceId exists",
                   provider.discoverers.get(DID2));
    }

    @Test
    public void unknownPktCtx() {

        PacketContext pktCtx = new TestPacketContext(deviceService.getDevice(DID2));

        testProcessor.process(pktCtx);
        assertFalse("Context should still be free", pktCtx.isHandled());
    }

    @Test
    public void knownPktCtx() {
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID1));
        deviceListener.event(deviceEvent(DeviceEvent.Type.DEVICE_ADDED, DID2));
        PacketContext pktCtx = new TestPacketContext(deviceService.getDevice(DID2));


        testProcessor.process(pktCtx);

        assertTrue("Link not detected", detectedLink(DID1, DID2));

    }


    @After
    public void tearDown() {
        provider.deactivate();
        provider.providerRegistry = null;
        provider.deviceService = null;
        provider.packetSevice = null;
    }

    private DeviceEvent deviceEvent(DeviceEvent.Type type, DeviceId did) {
        return new DeviceEvent(type, deviceService.getDevice(did));

    }

    private DeviceEvent portEvent(DeviceEvent.Type type, DeviceId did, PortNumber port) {
        return new  DeviceEvent(type, deviceService.getDevice(did),
                                deviceService.getPort(did, port));
    }

    private DeviceEvent portEvent(DeviceEvent.Type type, DeviceId did, Port port) {
        return new  DeviceEvent(type, deviceService.getDevice(did), port);
    }

    private Port port(DeviceId did, long port, boolean enabled) {
        return new DefaultPort(deviceService.getDevice(did),
                               PortNumber.portNumber(port), enabled);
    }


    private boolean vanishedDpid(DeviceId... dids) {
        for (int i = 0; i < dids.length; i++) {
            if (!providerService.vanishedDpid.contains(dids[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean vanishedPort(Long... ports) {
        for (int i = 0; i < ports.length; i++) {
            if (!providerService.vanishedPort.contains(ports[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean detectedLink(DeviceId src, DeviceId dst) {
        for (DeviceId key : providerService.discoveredLinks.keySet()) {
            if (key.equals(src)) {
                return providerService.discoveredLinks.get(src).equals(dst);
            }
        }
        return false;
    }


    private class TestLinkRegistry implements LinkProviderRegistry {

        @Override
        public LinkProviderService register(LinkProvider provider) {
            providerService = new TestLinkProviderService(provider);
            return providerService;
        }

        @Override
        public void unregister(LinkProvider provider) {
        }

        @Override
        public Set<ProviderId> getProviders() {
            return null;
        }

    }

    private class TestLinkProviderService
            extends AbstractProviderService<LinkProvider>
            implements LinkProviderService {

        List<DeviceId> vanishedDpid = Lists.newLinkedList();
        List<Long> vanishedPort = Lists.newLinkedList();
        Map<DeviceId, DeviceId> discoveredLinks = Maps.newHashMap();

        protected TestLinkProviderService(LinkProvider provider) {
            super(provider);
        }

        @Override
        public void linkDetected(LinkDescription linkDescription) {
            DeviceId sDid = linkDescription.src().deviceId();
            DeviceId dDid = linkDescription.dst().deviceId();
            discoveredLinks.put(sDid, dDid);
        }

        @Override
        public void linkVanished(LinkDescription linkDescription) {
            // TODO Auto-generated method stub

        }

        @Override
        public void linksVanished(ConnectPoint connectPoint) {
            vanishedPort.add(connectPoint.port().toLong());

        }

        @Override
        public void linksVanished(DeviceId deviceId) {
            vanishedDpid.add(deviceId);
        }


    }



    private class TestPacketContext implements PacketContext {

        protected Device device;
        protected boolean blocked = false;

        public TestPacketContext(Device dev) {
            device = dev;
        }

        @Override
        public long time() {
            return 0;
        }

        @Override
        public InboundPacket inPacket() {
            ONOSLLDP lldp = new ONOSLLDP();
            lldp.setChassisId(device.chassisId());
            lldp.setPortId((int) pd1.number().toLong());
            lldp.setDevice(deviceService.getDevice(DID1).id().toString());


            Ethernet ethPacket = new Ethernet();
            ethPacket.setEtherType(Ethernet.TYPE_LLDP);
            ethPacket.setDestinationMACAddress(ONOSLLDP.LLDP_NICIRA);
            ethPacket.setPayload(lldp);
            ethPacket.setPad(true);



            ethPacket.setSourceMACAddress("DE:AD:BE:EF:BA:11");

            ConnectPoint cp = new ConnectPoint(device.id(), pd3.number());

            return new DefaultInboundPacket(cp, ethPacket,
                                            ByteBuffer.wrap(ethPacket.serialize()));

        }

        @Override
        public OutboundPacket outPacket() {
            return null;
        }

        @Override
        public TrafficTreatment.Builder treatmentBuilder() {
            return null;
        }

        @Override
        public void send() {

        }

        @Override
        public boolean block() {
            blocked = true;
            return blocked;
        }

        @Override
        public boolean isHandled() {
            return blocked;
        }

    }

    private class TestPacketService implements PacketService {

        @Override
        public void addProcessor(PacketProcessor processor, int priority) {
            testProcessor = processor;
        }

        @Override
        public void removeProcessor(PacketProcessor processor) {

        }

        @Override
        public void emit(OutboundPacket packet) {

        }
    }

    private class TestDeviceService extends DeviceServiceAdapter {

        private Map<DeviceId, Device> devices = new HashMap<>();
        private final ArrayListMultimap<DeviceId, Port> ports =
                ArrayListMultimap.create();

        public TestDeviceService() {
            Device d1 = new DefaultDevice(ProviderId.NONE, DID1, Device.Type.SWITCH,
                                          "TESTMF", "TESTHW", "TESTSW", "TESTSN", new ChassisId());
            Device d2 = new DefaultDevice(ProviderId.NONE, DID2, Device.Type.SWITCH,
                                          "TESTMF", "TESTHW", "TESTSW", "TESTSN", new ChassisId());
            devices.put(DID1, d1);
            devices.put(DID2, d2);

            pd1 = new DefaultPort(d1, PortNumber.portNumber(1), true);
            pd2 = new DefaultPort(d1, PortNumber.portNumber(2), true);
            pd3 = new DefaultPort(d2, PortNumber.portNumber(1), true);
            pd4 = new DefaultPort(d2, PortNumber.portNumber(2), true);

            ports.putAll(DID1, Lists.newArrayList(pd1, pd2));
            ports.putAll(DID2, Lists.newArrayList(pd3, pd4));


        }

        @Override
        public int getDeviceCount() {
            return devices.values().size();
        }

        @Override
        public Iterable<Device> getDevices() {
            return Collections.emptyList();
        }

        @Override
        public Device getDevice(DeviceId deviceId) {
            return devices.get(deviceId);
        }

        @Override
        public MastershipRole getRole(DeviceId deviceId) {
            return MastershipRole.MASTER;
        }

        @Override
        public List<Port> getPorts(DeviceId deviceId) {
            return ports.get(deviceId);
        }

        @Override
        public Port getPort(DeviceId deviceId, PortNumber portNumber) {
            for (Port p : ports.get(deviceId)) {
                if (p.number().equals(portNumber)) {
                    return p;
                }
            }
            return null;
        }

        @Override
        public boolean isAvailable(DeviceId deviceId) {
            return true;
        }

        @Override
        public void addListener(DeviceListener listener) {
            deviceListener = listener;

        }

        @Override
        public void removeListener(DeviceListener listener) {

        }
    }

    private final class TestMasterShipService implements MastershipService {

        @Override
        public MastershipRole getLocalRole(DeviceId deviceId) {
            return MastershipRole.MASTER;
        }

        @Override
        public MastershipRole requestRoleFor(DeviceId deviceId) {
            return null;
        }

        @Override
        public void relinquishMastership(DeviceId deviceId) {

        }

        @Override
        public NodeId getMasterFor(DeviceId deviceId) {
            return null;
        }

        @Override
        public Set<DeviceId> getDevicesOf(NodeId nodeId) {
            return null;
        }

        @Override
        public void addListener(MastershipListener listener) {

        }

        @Override
        public void removeListener(MastershipListener listener) {

        }

        @Override
        public RoleInfo getNodesFor(DeviceId deviceId) {
            return new RoleInfo(new NodeId("foo"), Collections.<NodeId>emptyList());
        }
    }


}

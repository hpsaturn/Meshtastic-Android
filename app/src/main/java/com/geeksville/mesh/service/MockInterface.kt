package com.geeksville.mesh.service

import com.geeksville.android.Logging
import com.geeksville.mesh.*
import com.geeksville.mesh.model.getInitials
import com.google.protobuf.ByteString
import okhttp3.internal.toHexString

/** A simulated interface that is used for testing in the simulator */
class MockInterface(private val service: RadioInterfaceService) : Logging, IRadioInterface {
    companion object : Logging {

        const val interfaceName = "m"
    }

    private var messageCount = 50

    // an infinite sequence of ints
    private val messageNumSequence = generateSequence { messageCount++ }.iterator()

    init {
        info("Starting the mock interface")
        service.onConnect() // Tell clients they can use the API
    }

    override fun handleSendToRadio(p: ByteArray) {
        val pr = MeshProtos.ToRadio.parseFrom(p)

        when {
            pr.wantConfigId != 0 -> sendConfigResponse(pr.wantConfigId)
            pr.hasPacket() && pr.packet.wantAck -> sendFakeAck(pr)
            else -> info("Ignoring data sent to mock interface $pr")
        }
    }

    override fun close() {
        info("Closing the mock interface")
    }

    /// Generate a fake text message from a node
    private fun makeTextMessage(numIn: Int) =
        MeshProtos.FromRadio.newBuilder().apply {
            packet = MeshProtos.MeshPacket.newBuilder().apply {
                id = messageNumSequence.next()
                from = numIn
                to = 0xffffffff.toInt() // ugly way of saying broadcast
                rxTime = (System.currentTimeMillis() / 1000).toInt()
                rxSnr = 1.5f
                decoded = MeshProtos.SubPacket.newBuilder().apply {
                    data = MeshProtos.Data.newBuilder().apply {
                        portnum = Portnums.PortNum.TEXT_MESSAGE_APP
                        payload = ByteString.copyFromUtf8("This simulated node sends Hi!")
                    }.build()
                }.build()
            }.build()
        }


    private fun makeAck(fromIn: Int, toIn: Int, msgId: Int) =
        MeshProtos.FromRadio.newBuilder().apply {
            packet = MeshProtos.MeshPacket.newBuilder().apply {
                id = messageNumSequence.next()
                from = fromIn
                to = toIn
                rxTime = (System.currentTimeMillis() / 1000).toInt()
                rxSnr = 1.5f
                decoded = MeshProtos.SubPacket.newBuilder().apply {
                    data = MeshProtos.Data.newBuilder().apply {
                        successId = msgId
                    }.build()
                }.build()
            }.build()
        }

    /// Send a fake ack packet back if the sender asked for want_ack
    private fun sendFakeAck(pr: MeshProtos.ToRadio) {
        service.handleFromRadio(makeAck(pr.packet.to, pr.packet.from, pr.packet.id).build().toByteArray())
    }

    private fun sendConfigResponse(configId: Int) {
        debug("Sending mock config response")

        /// Generate a fake node info entry
        fun makeNodeInfo(numIn: Int, lat: Double, lon: Double) =
            MeshProtos.FromRadio.newBuilder().apply {
                nodeInfo = MeshProtos.NodeInfo.newBuilder().apply {
                    num = numIn
                    user = MeshProtos.User.newBuilder().apply {
                        id = DataPacket.nodeNumToDefaultId(numIn)
                        longName = "Sim " + num.toHexString()
                        shortName = getInitials(longName)
                    }.build()
                    position = MeshProtos.Position.newBuilder().apply {
                        latitudeI = Position.degI(lat)
                        longitudeI = Position.degI(lon)
                        batteryLevel = 42
                        altitude = 35
                        time = (System.currentTimeMillis() / 1000).toInt()
                    }.build()
                }.build()
            }

        // Simulated network data to feed to our app
         val MY_NODE = 0x42424242
        val packets = arrayOf(
            // MyNodeInfo
            MeshProtos.FromRadio.newBuilder().apply {
                myInfo = MeshProtos.MyNodeInfo.newBuilder().apply {
                    myNodeNum = MY_NODE
                    region = "TW"
                    numChannels = 7
                    hwModel = "Sim"
                    packetIdBits = 32
                    nodeNumBits = 32
                    currentPacketId = 1
                    messageTimeoutMsec = 5 * 60 * 1000
                    firmwareVersion = service.getString(R.string.cur_firmware_version)
                }.build()
            },

            // RadioConfig
            MeshProtos.FromRadio.newBuilder().apply {
                radio = MeshProtos.RadioConfig.newBuilder().apply {

                    preferences = MeshProtos.RadioConfig.UserPreferences.newBuilder().apply {
                        region = MeshProtos.RegionCode.TW
                        // FIXME set critical times?
                    }.build()

                    channel = MeshProtos.ChannelSettings.newBuilder().apply {
                        // we just have an empty listing so that the default channel works
                    }.build()
                }.build()
            },

            // Fake NodeDB
            makeNodeInfo(MY_NODE, 32.776665, -96.796989), // dallas
            makeNodeInfo(MY_NODE + 1, 32.960758, -96.733521), // richardson

            MeshProtos.FromRadio.newBuilder().apply {
                configCompleteId = configId
            },

            // Done with config response, now pretend to receive some text messages

            makeTextMessage(MY_NODE + 1)
        )

        packets.forEach { p ->
            service.handleFromRadio(p.build().toByteArray())
        }
    }
}

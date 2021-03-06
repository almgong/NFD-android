package net.named_data.nfd.wifidirect.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;

import net.named_data.nfd.wifidirect.callback.GenericCallback;
import net.named_data.nfd.wifidirect.model.Peer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

/**
 * WiFi Direct Broadcast receiver. Does not deviate too much
 * from the standard WiFi Direct broadcast receiver seen in the official
 * android docs.
 *
 * Created by allengong on 11/5/16.
 */

public class WDBroadcastReceiver extends BroadcastReceiver {

    // volatile variables accessed in multiple threads
    public static volatile String groupOwnerAddress;
    public static volatile String myAddress;

    private static final String TAG = "WDBroadcastReceiver";

    // WifiP2p
    private WifiP2pManager mManager;
    private WifiP2pManager.Channel mChannel;

    // the controller
    private NDNController mController;

    public WDBroadcastReceiver(WifiP2pManager manager, WifiP2pManager.Channel channel) {
        super();

        this.mManager = manager;
        this.mChannel = channel;
        this.mController = NDNController.getInstance();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();

        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            // Check to see if Wi-Fi is enabled and notify appropriate activity

            Log.d(TAG, "wifi enabled check");
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                // Wifi P2P is enabled
                Log.d(TAG, "WIFI IS ENABLED");
            } else {
                // Wi-Fi P2P is not enabled
                Log.d(TAG, "WIFI IS NOT ENABLED");
            }

        } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            // Call WifiP2pManager.requestPeers() to get a list of current peers

            Log.d(TAG, "peers changed!");

            // request available peers from the wifi p2p manager. This is an
            // asynchronous call and the calling activity is notified with a
            // callback on PeerListListener.onPeersAvailable()
            if (mManager != null) {
                mManager.requestPeers(mChannel, new WifiP2pManager.PeerListListener() {
                    @Override
                    public void onPeersAvailable(WifiP2pDeviceList peers) {
                        Log.d(TAG,
                                String.format("Peers available: %d", peers.getDeviceList().size()));

                        // create temporary map of new peers {macAddress:device, ...}
                        HashMap<String, WifiP2pDevice> newPeers =
                                new HashMap<>(peers.getDeviceList().size());
                        for (WifiP2pDevice device : peers.getDeviceList()) {
                            newPeers.put(device.deviceAddress, device);
                        }

                        // iterate through currently connected peers, noting already connected
                        // peers and removing those that are no longer available
                        Iterator<String> currPeersIterator = mController.getConnectedPeers().iterator();
                        while (currPeersIterator.hasNext()) {
                            String peerMacAddr = currPeersIterator.next();
                            if (newPeers.containsKey(peerMacAddr)) {
                                newPeers.remove(peerMacAddr);
                            } else {
                                // this means the current peer is no longer available
                                //mController.removeConnectedPeer(peerMacAddr);
                                currPeersIterator.remove();
                            }
                        }

                        // now go ahead and add as many peers as possible
                        Set<String> connectedPeers = mController.getConnectedPeers();
                        Log.d(TAG, "Number of new peers that can be added: " + newPeers.size());
                        for (String peerMacAddr : newPeers.keySet()) {

                            WifiP2pDevice device = newPeers.get(peerMacAddr);
                            Peer peer = new Peer();
                            peer.setDeviceAddress(device.deviceAddress);
                            peer.setName(device.deviceName);

                            // if mController is accepting a new peer, connect to it
                            if (!connectedPeers.contains(device.deviceAddress)) {
                                if (mController.logConnectedPeer(peer)) {
                                    Log.d(TAG, "Connecting to " + peer.getDeviceAddress());
                                    connect(device);
                                } else {
                                    Log.d(TAG, "Maximum number of connected peers reached.");
                                }
                            } else {
                                Log.d(TAG, "Peer: " + peer.getDeviceAddress() + " already in group, skip.");
                            }
                        }
                    }
                });
            }

        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            // Respond to new connection or disconnections

            Log.d(TAG, "p2pconnection changed check");
            if (mManager == null) {
                Log.d(TAG, "mManager is null, skipping...");
                return;
            }

            NetworkInfo networkInfo = (NetworkInfo) intent
                    .getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

            if (networkInfo.isConnected()) {
                // We are connected with the other device, request connection
                // info to find group owner IP
                mManager.requestConnectionInfo(mChannel, new WifiP2pManager.ConnectionInfoListener() {
                    @Override
                    public void onConnectionInfoAvailable(WifiP2pInfo info) {
                        Log.d(TAG, "connection info is available!!");

                        // check if group formation was successful
                        if (info.groupFormed) {

                            // group owner address
                            groupOwnerAddress = info.groupOwnerAddress.getHostAddress();

                            // this device's address, which is now available
                            myAddress = IPAddress.getLocalIPAddress();
                            Log.d(TAG, "My WiFi Direct IP address is: " + myAddress);

                            if (!mController.getHasRegisteredOwnLocalhop()) {
                                // do so now
                                mController.registerOwnLocalhop();
                                Log.d(TAG, "registerOwnLocalhop() called...");
                            } else {
                                Log.d(TAG, "already registered own /localhop prefix.");
                            }

                            if (info.isGroupOwner) {
                                // Do whatever tasks are specific to the group owner.
                                // One common case is creating a server thread and accepting
                                // incoming connections.
                                Log.d(TAG, "I am the group owner, wait for probe interests from peers...");
                                mController.setIsGroupOwner(true);
                            } else {
                                // non group owner
                                // The other device acts as the client. In this case,
                                // you'll want to create a client thread that connects to the group
                                // owner.
                                Log.d(TAG, "I am not the group owner, create a face towards GO.");
                                mController.setIsGroupOwner(false);

                                // skip if already part of this group
                                if (mController.getFaceIdForPeer(groupOwnerAddress) != -1) {
                                    return;
                                }

                                // create a callback that will register the /localhop/wifidirect/<go-addr> prefix
                                GenericCallback cb = new GenericCallback() {
                                    @Override
                                    public void doJob() {
                                        Log.d(TAG, "registering " + NDNController.PROBE_PREFIX + "/" + groupOwnerAddress);
                                        String[] prefixes = new String[1];
                                        prefixes[0] = NDNController.PROBE_PREFIX + "/" + groupOwnerAddress;
                                        mController.ribRegisterPrefix(mController.getFaceIdForPeer(groupOwnerAddress),
                                                prefixes);
                                    }
                                };

                                // create UDP face towards GO, with callback to register /localhop/... prefix
                                mController.createFace(groupOwnerAddress, NDNController.URI_TRANSPORT_PREFIX, cb);
                            }
                        }
                    }
                });
            }

        } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
            // Respond to this device's wifi state changing
            Log.d(TAG, "wifi state changed check");
        } else if (WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)) {
            // if discovery (scanning) has either stopped or resumed
            switch(intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)) {
                case WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED:
                    Log.d(TAG, "Wifip2p discovery started.");
                    break;
                case WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED:
                    Log.d(TAG, "Wifipsp discovery stopped.");
                    break;
                default:
                    Log.d(TAG, "WIFI_P2P_DISCOVERY_CHANGED_ACTION returned other reason.");
            }
        }
    }

    /**
     * Convenience method to connect to a WifiP2p peer.
     * @param peerDevice the WifiP2pDevice instance to connect to
     */
    public void connect(final WifiP2pDevice peerDevice) {

        // config
        final WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = peerDevice.deviceAddress;
        config.wps.setup = WpsInfo.PBC;

        // attempt to connect
        mManager.connect(mChannel, config, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess() {
                // onReceive() above will receive an intent if appropriate
                Log.d(TAG, "Connect successful for: " + config.deviceAddress);
            }

            @Override
            public void onFailure(int reason) {
                // remove log of this device from connectedPeers, if it had
                // been previously added
                mController.getConnectedPeers().remove(config.deviceAddress);
            }
        });
    }

    /**
     * Resets all persistent state accumulated through
     * normal operation.
     */
    public static void cleanUp() {
        myAddress = null;
        groupOwnerAddress = null;
    }
}

package net.named_data.nfd.wifidirect.runnable;

import android.util.Log;

import com.intel.jndn.management.ManagementException;
import com.intel.jndn.management.Nfdc;
import com.intel.jndn.management.types.FibEntry;

import net.named_data.jndn.Data;
import net.named_data.jndn.Face;
import net.named_data.jndn.Interest;
import net.named_data.jndn.Name;
import net.named_data.jndn.OnData;
import net.named_data.jndn.OnTimeout;
import net.named_data.nfd.wifidirect.callback.ProbeOnData;
import net.named_data.nfd.wifidirect.model.Peer;
import net.named_data.nfd.wifidirect.utils.IPAddress;
import net.named_data.nfd.wifidirect.utils.NDNController;
import net.named_data.nfd.wifidirect.utils.WDBroadcastReceiver;

import java.io.IOException;
import java.util.List;

/**
 * Probes network for data prefixes, as specified in protocol.
 * Created by allengong on 12/9/16.
 */
public class ProbeRunnable implements Runnable {
    private static final String TAG = "ProbeRunnable";
    private final int MAX_TIMEOUTS_ALLOWED = 5;
    private NDNController mController = NDNController.getInstance();

    private Face mFace = mController.getLocalHostFace();

    @Override
    public void run() {
        try {
            if (IPAddress.getLocalIPAddress() == null) {

                // this means that a disconnect has recently occurred and this device
                // is no longer a part of a group (WDBroadcastReceiver.myAddress is this
                // device's previous WD IP)
                if (WDBroadcastReceiver.myAddress != null) {
                    Log.d(TAG, "A disconnect has been detected, refreshing state...");

                    WDBroadcastReceiver.myAddress = null;

                    // unregister the previous "/localhop/wifidirect/..." prefix
                    mController.unregisterOwnLocalhop();

                    // clear state of connected+active peers (in prep for reconnection)
                    mController.getConnectedPeers().clear();
                    mController.getIpsOfLoggedPeers().clear();

                    // most likely will have a new IP to register "/localhop/wifidirect/<IP>"
                    // call this so that the next time a group is joined a new local prefix
                    // registration will occur
                    mController.setHasRegisteredOwnLocalhop(false);

                    // ensure that peer diiscovery is running, if it had not been before
                    mController.startDiscoveringPeers();
                } else {
                    Log.d(TAG, "Skip this iteration due to null WD ip.");
                }

            } else {
                // enumerate FIB entries
                List<FibEntry> fibEntries = Nfdc.getFibList(mFace);

                // look only for the ones related to /localhop/wifidirect/xxx
                for (FibEntry entry : fibEntries) {
                    String prefix = entry.getPrefix().toString();
                    final String[] prefixArr = prefix.split("/");
                    if (prefix.startsWith(NDNController.PROBE_PREFIX) && !prefixArr[prefixArr.length - 1].equals(WDBroadcastReceiver.myAddress)) {

                        // send interest to this peer
                        Interest interest = new Interest(new Name(prefix + "/" + WDBroadcastReceiver.myAddress + "/probe?" + System.currentTimeMillis()));
                        interest.setMustBeFresh(true);
                        Log.d(TAG, "Sending interest: " + interest.getName().toString());
                        mFace.expressInterest(interest, new OnData() {
                            @Override
                            public void onData(Interest interest, Data data) {
                                (new ProbeOnData()).doJob(interest, data);
                                Peer peer = NDNController.getInstance().getPeerByIp(prefixArr[prefixArr.length - 1]);
                                peer.setNumProbeTimeouts(0);    // peer responded, so reset timeout counter
                            }
                        }, new OnTimeout() {
                            @Override
                            public void onTimeout(Interest interest) {
                                Peer peer = NDNController.getInstance().getPeerByIp(prefixArr[prefixArr.length - 1]);
                                if (peer == null) {
                                    Log.d(TAG, "No peer information available to track timeout.");
                                    return;
                                }

                                Log.d(TAG, "Timeout for interest: " + interest.getName().toString() +
                                        " Attempts: " + (peer.getNumProbeTimeouts() + 1));

                                if (peer.getNumProbeTimeouts() + 1 >= MAX_TIMEOUTS_ALLOWED) {
                                    // declare peer as disconnected from group
                                    NDNController.getInstance().removePeer(prefixArr[prefixArr.length - 1]);
                                } else {
                                    peer.setNumProbeTimeouts(peer.getNumProbeTimeouts() + 1);
                                }
                            }
                        });
                    }
                }
            }
        } catch (ManagementException me) {
            Log.e(TAG, "Something went wrong with acquiring the FibList.");
            me.printStackTrace();
        } catch (IOException ioe) {
            Log.e(TAG, "Something went wrong with sending a probe interest.");
            ioe.printStackTrace();
        }
    }
}

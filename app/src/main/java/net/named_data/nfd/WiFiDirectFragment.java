package net.named_data.nfd;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import net.named_data.nfd.wifidirect.model.Peer;
import net.named_data.nfd.wifidirect.utils.NDNController;
import net.named_data.nfd.wifidirect.utils.WDBroadcastReceiver;

import java.util.ArrayList;


/**
 * A simple {@link Fragment} subclass.
 * Activities that contain this fragment must implement the
 * Use the {@link WiFiDirectFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class WiFiDirectFragment extends Fragment {

    public WiFiDirectFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @return A new instance of fragment WiFiDirectFragment.
     */
    public static WiFiDirectFragment newInstance() {
        return new WiFiDirectFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        m_sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        m_handler = new Handler();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_wi_fi_direct, container, false);

        // init UI elements
        m_wdGroupConnStatus = (TextView) view.findViewById(R.id.wd_group_conn_status_textview);
        m_wdIpAddress = (TextView) view.findViewById(R.id.wd_ip_address_textview);
        m_wdSwitch = (Switch) view.findViewById(R.id.wd_switch);

        if (m_sharedPreferences.getBoolean(PREF_WIFIDIRECT_STATUS, false)) {
            m_wdSwitch.setChecked(true);
            m_wdGroupConnStatus.setText(TEXT_GROUP_PENDING);
            startNDNOverWifiDirect();
            startUiUpdateLoop();
        } else {
            // the button was off, make any desired UI changes
            m_wdGroupConnStatus.setText("");
        }

        m_wdSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                // store state of switch
                m_sharedPreferences.edit().putBoolean(PREF_WIFIDIRECT_STATUS, isChecked).apply();

                if (isChecked) {
                    startNDNOverWifiDirect();
                    startUiUpdateLoop();
                    m_wdGroupConnStatus.setText(TEXT_GROUP_PENDING);
                } else {
                    stopNDNOverWifiDirect();
                    stopUiUpdateLoop();
                    resetUi();
                }
            }
        });

        // list view for displaying peers
        m_wdPeerListview = (ListView) view.findViewById(R.id.wd_peers_listview);
        m_peerIps = new ArrayList<>(NDNController.getInstance().getIpsOfLoggedPeers());

        m_arrayAdapter = new ArrayAdapter<>(getActivity(),
                android.R.layout.simple_list_item_1, m_peerIps);
        m_wdPeerListview.setAdapter(m_arrayAdapter);
        m_wdPeerListview.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Peer selectedPeer =
                        NDNController
                        .getInstance()
                        .getPeerByIp((String)parent.getItemAtPosition(position));

                // toast a quick message!
                if (selectedPeer == null) {
                    Toast.makeText(getActivity(), "The peer is no longer available.",
                            Toast.LENGTH_LONG).show();
                } else {
                    String peerInfo = "FaceId: " + selectedPeer.getFaceId() + " - Recent Timeouts: " +
                            selectedPeer.getNumProbeTimeouts();
                    Toast.makeText(getActivity(), peerInfo, Toast.LENGTH_LONG).show();
                }
            }
        });

        return view;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
    }

    @Override
    public void onDetach() {
        super.onDetach();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopUiUpdateLoop();
    }

    @Override
    public void onResume() {
        super.onResume();
        startUiUpdateLoop();
    }

    private void startNDNOverWifiDirect() {
        // this is set so that NDNController has context to start appropriate services
        NDNController.getInstance().setWifiDirectContext(getActivity());

        // main wrapper function that begins the protocol
        NDNController.getInstance().start();
    }

    private void stopNDNOverWifiDirect() {
        // main wrapper function that stops all elements of the protocol
        NDNController.getInstance().stop();

        // to remove any accumulated state over normal execution of the protocol
        NDNController.getInstance().cleanUp();
    }

    private void startUiUpdateLoop() {
        // periodically check for changed state
        // to display to user
        m_handler.postDelayed(m_UiUpdateRunnable , UI_UPDATE_DELAY_MS);
    }

    private void stopUiUpdateLoop() {
        m_handler.removeCallbacks(m_UiUpdateRunnable);
    }

    private void resetUi() {
        // simply resets what is displayed to user
        m_wdIpAddress.setText("");
        m_wdGroupConnStatus.setText("");
        m_peerIps.clear();
        m_arrayAdapter.notifyDataSetChanged();
    }

    ////////////////////////////////////////////////////////////////////////////////////

    private ListView m_wdPeerListview;
    private Switch m_wdSwitch;
    private TextView m_wdGroupConnStatus;
    private TextView m_wdIpAddress;

    private Handler m_handler;
    private Runnable m_UiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (WDBroadcastReceiver.myAddress != null) {
                m_wdGroupConnStatus.setText(TEXT_GROUP_CONNECTED);
                m_wdIpAddress.setText(WDBroadcastReceiver.myAddress);
            }

            // refresh the list of peers
            m_peerIps.clear();
            m_peerIps.addAll(NDNController.getInstance().getIpsOfLoggedPeers());
            m_arrayAdapter.notifyDataSetChanged();

            // call again in X seconds
            m_handler.postDelayed(m_UiUpdateRunnable, UI_UPDATE_DELAY_MS);
        }
    };
    private SharedPreferences m_sharedPreferences;
    private ArrayList<String> m_peerIps;
    private ArrayAdapter<String> m_arrayAdapter;

    private final String TEXT_GROUP_CONNECTED = "Connected to group.";
    private final String TEXT_GROUP_PENDING = "Scanning...";
    private final int UI_UPDATE_DELAY_MS = 5000;

    private static final String PREF_WIFIDIRECT_STATUS = "WIFIDIRECT_STATUS";
}

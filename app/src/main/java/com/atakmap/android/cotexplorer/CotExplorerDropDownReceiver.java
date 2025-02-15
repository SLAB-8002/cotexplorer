package com.atakmap.android.cotexplorer;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.gui.EditText;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.cotexplorer.plugin.R;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;

import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.android.util.AbstractMapItemSelectionTool;
import com.atakmap.comms.CommsLogger;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.android.cotexplorer.plugin.PluginNativeLoader;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;


public class CotExplorerDropDownReceiver extends DropDownReceiver implements
        OnStateListener, CommsLogger, View.OnClickListener {

    public static final String TAG = "cotexplorer";

    public static final String SHOW_PLUGIN = "com.atakmap.android.cotexplorer.SHOW_PLUGIN";
    private final Context pluginContext;
    private final Context appContext;
    private final MapView mapView;
    private final View mainView;
    final InspectionMapItemSelectionTool imis;

    private boolean paused = false;
    private RecyclerView cotexplorerlog = null;
    private LogAdapter logAdapter;
    private Button sendBtn, clearBtn, pauseBtn, saveBtn, inspectBtn = null;
    private ImageButton filterBtn = null;
    private SharedPreferences _sharedPreference = null;
    private String cotFilter = "";
    private List<String> fullLog = new ArrayList<>();
    private static boolean isRegistered = false;

    /**************************** CONSTRUCTOR *****************************/

    public CotExplorerDropDownReceiver(final MapView mapView, final Context context) {
        super(mapView);
        this.pluginContext = context;
        this.appContext = mapView.getContext();
        this.mapView = mapView;
        this.imis = new InspectionMapItemSelectionTool();

        if (!isRegistered) {
            CommsMapComponent.getInstance().registerCommsLogger(this);
            isRegistered = true; // Prevent duplicate registrations
        }

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        this.mainView = inflater.inflate(R.layout.main_layout, null);
        cotexplorerlog = mainView.findViewById(R.id.cotexplorerlog);
        logAdapter = new LogAdapter(convertToSpannableList(fullLog));// Initialize adapter with your log list
        cotexplorerlog.setLayoutManager(new LinearLayoutManager(context));
        cotexplorerlog.setAdapter(logAdapter);
        clearBtn = mainView.findViewById(R.id.clearBtn);
        pauseBtn = mainView.findViewById(R.id.pauseBtn);
        filterBtn = mainView.findViewById(R.id.filterBtn);
        saveBtn = mainView.findViewById(R.id.saveBtn);
        sendBtn = mainView.findViewById(R.id.sendBtn);
        inspectBtn = mainView.findViewById(R.id.inspectBtn);

        _sharedPreference = PreferenceManager.getDefaultSharedPreferences(mapView.getContext().getApplicationContext());

        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {


                EditText et = new EditText(mapView.getContext());

                AlertDialog.Builder alertDialog = new AlertDialog.Builder(mapView.getContext());
                alertDialog.setTitle("Enter CoT to send");
                alertDialog.setView(et);
                alertDialog.setNegativeButton("Cancel", null);
                alertDialog.setPositiveButton("Send", (dialogInterface, i) -> {
                    CotEvent cot = CotEvent.parse(et.getText().toString());
                    if (cot.isValid()) {
                        CotMapComponent.getExternalDispatcher().dispatch(cot);
                    } else {
                        Toast.makeText(mapView.getContext(), "Invalid CoT", Toast.LENGTH_LONG).show();
                    }
                });
                alertDialog.show();
            }
        });

        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Get the directory path from PluginNativeLoader
                String dirPath = PluginNativeLoader.getCotExplorerDir();
                Log.d(TAG, "Directory path: " + dirPath);

                // Generate timestamped filename
                String timestamp = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.getDefault())
                        .format(new Date());
                File file = new File(dirPath, "cotexplorer-" + timestamp + ".txt");

                try {
                    FileWriter fw = new FileWriter(file);
                    // Iterate through fullLog and write each entry to the file
                    for (String log : fullLog) {
                        fw.write(log + "\n");
                    }
                    fw.flush();
                    fw.close();

                    Toast.makeText(mapView.getContext(),
                            "Log written to " + file.getAbsolutePath(),
                            Toast.LENGTH_LONG).show();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to save log file", e);
                    Toast.makeText(mapView.getContext(),
                            "Error saving log file",
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                fullLog.clear(); // Clear the list of logs
                logAdapter.clearLogs(); // Clear the logs in the adapter and notify the RecyclerView
            }
        });

        pauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (paused) {
                    pauseBtn.setText("Pause");
                    paused = false;
                } else {
                    pauseBtn.setText("Paused");
                    paused = true;
                }
            }
        });

        filterBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AlertDialog.Builder alertBuilder = new AlertDialog.Builder(mapView.getContext());
                alertBuilder.setTitle("Set filter");
                final EditText input = new EditText(mapView.getContext());
                input.setText(cotFilter);
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                alertBuilder.setView(input);

                alertBuilder.setPositiveButton("OK", (dialogInterface, i) -> {
                    cotFilter = input.getText().toString().trim();
                    Log.i(TAG, "Filter set to: '" + cotFilter + "'");
                    _sharedPreference.edit().putString("plugin_cotexplorer_type", cotFilter).apply(); // Save immediately
                    applyFilter();
                });

                alertBuilder.setNegativeButton("Cancel", (dialogInterface, i) -> {});

                alertBuilder.setNeutralButton("Clear", (dialogInterface, i) -> {
                    cotFilter = ""; // Properly reset the filter
                    _sharedPreference.edit().remove("plugin_cotexplorer_type").apply(); // Ensure no old values persist
                    Log.i(TAG, "Filter cleared.");
                    applyFilter();
                });

                alertBuilder.setCancelable(true);
                alertBuilder.show();
            }
        });

        final BroadcastReceiver inspectionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                AtakBroadcast.getInstance().unregisterReceiver(this);
                final Button itemInspect = mainView
                        .findViewById(R.id.inspectBtn);
                itemInspect.setSelected(false);

                String uid = intent.getStringExtra("uid");
                if (uid == null)
                    return;

                MapItem mi = getMapView().getMapItem(uid);

                if (mi == null)
                    return;

                com.atakmap.coremap.log.Log.d(TAG, "class: " + mi.getClass());
                com.atakmap.coremap.log.Log.d(TAG, "type: " + mi.getType());

                final CotEvent cotEvent = CotEventFactory
                        .createCotEvent(mi);

                String val;
                if (cotEvent != null)
                    val = cotEvent.toString();
                else if (mi.hasMetaValue("nevercot"))
                    val = "map item set to never persist (nevercot)";
                else
                    val = "error turning a map item into CoT";

                AlertDialog.Builder builderSingle = new AlertDialog.Builder(
                        getMapView().getContext());
                TextView showText = new TextView(getMapView().getContext());
                showText.setText(val);
                showText.setTextIsSelectable(true);
                showText.setPadding(32, 32, 32, 32); // Add padding (left, top, right, bottom) in pixels
                showText.setOnLongClickListener(new View.OnLongClickListener() {

                    @Override
                    public boolean onLongClick(View v) {
                        // Copy the Text to the clipboard
                        ClipboardManager manager = (ClipboardManager) getMapView()
                                .getContext()
                                .getSystemService(Context.CLIPBOARD_SERVICE);
                        TextView showTextParam = (TextView) v;
                        manager.setText(showTextParam.getText());
                        Toast.makeText(v.getContext(),
                                "copied the data", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                });

                builderSingle.setTitle("CoT Inspector");
                builderSingle.setView(showText);
                builderSingle.setPositiveButton("Close", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss(); // Close the dialog
                    }
                });
                builderSingle.show();
            }
        };

        inspectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean val = inspectBtn.isSelected();
                if (val) {
                    imis.requestEndTool();
                } else {

                    AtakBroadcast.getInstance().registerReceiver(
                            inspectionReceiver,
                            new AtakBroadcast.DocumentedIntentFilter(
                                    "com.atakmap.android.cotexplorer.InspectionMapItemSelectionTool.Finished"));
                    Bundle extras = new Bundle();
                    ToolManagerBroadcastReceiver.getInstance().startTool(
                            "com.atakmap.android.cotexplorer.InspectionMapItemSelectionTool",
                            extras);

                }
                inspectBtn.setSelected(!val);
            }
        });

        CommsMapComponent.getInstance().registerCommsLogger(this);
    }

    private List<SpannableString> convertToSpannableList(List<String> logs) {
        List<SpannableString> spannableLogs = new ArrayList<>();
        for (String log : logs) {
            spannableLogs.add(new SpannableString(log));
        }
        return spannableLogs;
    }

    public class LogAdapter extends RecyclerView.Adapter<LogAdapter.LogViewHolder> {
        private List<SpannableString> logs;

        public LogAdapter(List<SpannableString> logs) {
            this.logs = logs;
        }

        public void addLog(SpannableString log) {
            logs.add(log);
            notifyItemInserted(logs.size() - 1);
        }

        public void updateLogs(List<SpannableString> newLogs) {
            logs.clear();
            logs.addAll(newLogs);
            notifyDataSetChanged(); // Properly update RecyclerView
        }

        // Method to clear logs
        public void clearLogs() {
            logs.clear();
            notifyDataSetChanged();
        }

        @Override
        public LogViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(android.R.layout.simple_list_item_1, parent, false);
            return new LogViewHolder(view);
        }

        @Override
        public void onBindViewHolder(LogViewHolder holder, int position) {
            SpannableString logEntry = logs.get(position);

            // Create a new SpannableStringBuilder to append the separator
            SpannableStringBuilder logWithSeparator = new SpannableStringBuilder();
            logWithSeparator.append(logEntry);
            logWithSeparator.append("\n----------\n"); // Add a newline separator

            holder.logText.setText(logWithSeparator);
            holder.logText.setTextIsSelectable(true);

            // Adjust text size dynamically
            float textSize = getDynamicTextSize(holder.logText.getContext());
            holder.logText.setTextSize(textSize);
        }

        private float getDynamicTextSize(Context context) {
            float screenWidthDp = context.getResources().getDisplayMetrics().widthPixels /
                    context.getResources().getDisplayMetrics().density;

            if (screenWidthDp >= 600) {
                return 16; // Tablets
            } else if (screenWidthDp >= 360) {
                return 14; // Phones
            } else {
                return 12; // Smaller devices
            }
        }

        @Override
        public int getItemCount() {
            return logs.size();
        }

        public class LogViewHolder extends RecyclerView.ViewHolder { // Removed static modifier
            TextView logText;

            public LogViewHolder(View itemView) {
                super(itemView);
                logText = itemView.findViewById(android.R.id.text1);
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (action != null && action.equals(SHOW_PLUGIN)) {
            showDropDown(mainView, HALF_WIDTH, FULL_HEIGHT,
                    FULL_WIDTH, HALF_HEIGHT, false, this);
        }
    }

    private void writeLog(final String log) {
        if (paused) return;

        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (fullLog) { // Prevent concurrent modification
                if (!fullLog.contains(log)) {
                    fullLog.add(log); // Ensure no duplicates
                }
            }

            SpannableString spannableLog = new SpannableString(log);

            if (!cotFilter.isEmpty() && log.contains(cotFilter)) {
                int start = log.indexOf(cotFilter);
                int end = start + cotFilter.length();
                spannableLog.setSpan(
                        new BackgroundColorSpan(0x80FFFF33), // Highlight match
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            }

            if (cotFilter.isEmpty() || log.contains(cotFilter)) {
                logAdapter.addLog(spannableLog);
                cotexplorerlog.scrollToPosition(logAdapter.getItemCount() - 1);
            }

            // Ensure UI updates when a filter is active
            if (!cotFilter.isEmpty()) {
                applyFilter();
            }
        });
    }

    private void applyFilter() {
        // Always fetch the latest filter value
        cotFilter = _sharedPreference.getString("plugin_cotexplorer_type", "").trim();
        Log.i(TAG, "Applying filter: '" + cotFilter + "'");

        List<String> logCopy;
        synchronized (fullLog) {
            logCopy = new ArrayList<>(fullLog);
        }

        List<SpannableString> filteredLogs = new ArrayList<>();

        for (String log : logCopy) {
            if (cotFilter.isEmpty() || log.contains(cotFilter)) {
                SpannableString spannableLog = new SpannableString(log);
                if (!cotFilter.isEmpty()) {
                    int start = log.indexOf(cotFilter);
                    int end = start + cotFilter.length();
                    spannableLog.setSpan(
                            new BackgroundColorSpan(0x80FFFF33),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                }
                filteredLogs.add(spannableLog);
            }
        }

        Log.i(TAG, "Filtered logs count: " + filteredLogs.size());
                logAdapter.updateLogs(filteredLogs);
        cotexplorerlog.scrollToPosition(logAdapter.getItemCount() - 1);
    }

    final class InspectionMapItemSelectionTool
            extends AbstractMapItemSelectionTool {
        public InspectionMapItemSelectionTool() {
            super(getMapView(),
                    "com.atakmap.android.cotexplorer.InspectionMapItemSelectionTool",
                    "com.atakmap.android.cotexplorer.InspectionMapItemSelectionTool.Finished",
                    "Select Map Item on the screen",
                    "Invalid Selection");
        }

        @Override
        protected boolean isItem(MapItem mi) {
            return true;
        }

    }

    @Override
    public void disposeImpl() {
        Log.i(TAG, "Unregistering logReceive()");
        CommsMapComponent.getInstance().unregisterCommsLogger(this);
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
    }

    @Override
    public void logSend(CotEvent cotEvent, String s) {
        Log.i(TAG, "Sent event: " + cotEvent.getType());

        // Ensure we always get the latest filter
        cotFilter = _sharedPreference.getString("plugin_cotexplorer_type", "").trim();
        String eventMessage = cotEvent.toString().trim(); // Use the full message
        Log.i(TAG, "Current filter: '" + cotFilter + "'");
        Log.i(TAG, "Full CoT Message: " + eventMessage);
        if (cotFilter.isEmpty()) {
            Log.i(TAG, "No filter applied, displaying event.");
            writeLog(String.format("S: %s", eventMessage));
        } else if (eventMessage.contains(cotFilter)) { // Check full message
            Log.i(TAG, "Log matches filter, adding to RecyclerView.");
            writeLog(String.format("S: %s", eventMessage));
        } else {
            Log.i(TAG, "Log does NOT match filter, skipping UI update.");
            Log.i(TAG, "eventMessage.contains(cotFilter) == " + eventMessage.contains(cotFilter));
        }
    }

    @Override
    public void logSend(CotEvent cotEvent, String[] strings) {
        Log.i(TAG, "Sent event: " + cotEvent.getType());

        // Ensure we always get the latest filter
        cotFilter = _sharedPreference.getString("plugin_cotexplorer_type", "").trim();
        String eventMessage = cotEvent.toString().trim(); // Use the full message
        Log.i(TAG, "Current filter: '" + cotFilter + "'");
        Log.i(TAG, "Full CoT Message: " + eventMessage);
        if (cotFilter.isEmpty()) {
            Log.i(TAG, "No filter applied, displaying event.");
            writeLog(String.format("S: %s", eventMessage));
        } else if (eventMessage.contains(cotFilter)) { // Check full message
            Log.i(TAG, "Log matches filter, adding to RecyclerView.");
            writeLog(String.format("S: %s", eventMessage));
        } else {
            Log.i(TAG, "Log does NOT match filter, skipping UI update.");
            Log.i(TAG, "eventMessage.contains(cotFilter) == " + eventMessage.contains(cotFilter));
        }
    }

    @Override
    public void logReceive(CotEvent cotEvent, String s, String s1) {
        Log.i(TAG, "Received event: " + cotEvent.getType());

        // Ensure we always get the latest filter
        cotFilter = _sharedPreference.getString("plugin_cotexplorer_type", "").trim();
        String eventMessage = cotEvent.toString().trim(); // Use the full message
        Log.i(TAG, "Current filter: '" + cotFilter + "'");
        Log.i(TAG, "Full CoT Message: " + eventMessage);

        if (cotFilter.isEmpty()) {
            Log.i(TAG, "No filter applied, displaying event.");
            writeLog(String.format("R: %s", eventMessage));
        } else if (eventMessage.contains(cotFilter)) { // Check full message
            Log.i(TAG, "Log matches filter, adding to RecyclerView.");
            writeLog(String.format("R: %s", eventMessage));
        } else {
            Log.i(TAG, "Log does NOT match filter, skipping UI update.");
            Log.i(TAG, "eventMessage.contains(cotFilter) == " + eventMessage.contains(cotFilter));
        }
    }

    @Override
    public void onClick(View view) {
        Log.i(TAG, "onClick");

    }
}
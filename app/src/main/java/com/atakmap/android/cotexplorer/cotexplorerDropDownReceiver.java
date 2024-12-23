package com.atakmap.android.cotexplorer;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;

import android.os.Bundle;
import android.os.Environment;
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
import com.atakmap.android.gui.AlertDialogHelper;
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
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;


public class cotexplorerDropDownReceiver extends DropDownReceiver implements
        OnStateListener, CommsLogger, View.OnClickListener {

    public static final String TAG = "cotexplorer";

    public static final String SHOW_PLUGIN = "com.atakmap.android.cotexplorer.SHOW_PLUGIN";
    private final Context pluginContext;
    private final Context appContext;
    private final MapView mapView;
    private final View mainView;
    final InspectionMapItemSelectionTool imis;

    private boolean paused = false;
    private TextView cotexplorerlog, pageCounter = null;
    private Button sendBtn, clearBtn, pauseBtn, saveBtn, inspectBtn, nextPageBtn, prevPageBtn = null;
    private ImageButton filterBtn = null;
    private SharedPreferences _sharedPreference = null;
    private String cotFilter = "";
    private List<String> fullLog = new ArrayList<>();
    private static final int maxDisplayedEntries = 20;
    private int currentPage = 1;
    private List<String> displayedLog = new ArrayList<>();
    private int totalPages = 1;

    /**************************** CONSTRUCTOR *****************************/

    public cotexplorerDropDownReceiver(final MapView mapView,
                                       final Context context) {
        super(mapView);
        this.pluginContext = context;
        this.appContext = mapView.getContext();
        this.mapView = mapView;
        this.imis = new InspectionMapItemSelectionTool(); // Initialize here

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        this.mainView = inflater.inflate(R.layout.main_layout, null);
        cotexplorerlog = mainView.findViewById(R.id.cotexplorerlog);
        clearBtn = mainView.findViewById(R.id.clearBtn);
        pauseBtn = mainView.findViewById(R.id.pauseBtn);
        filterBtn = mainView.findViewById(R.id.filterBtn);
        saveBtn = mainView.findViewById(R.id.saveBtn);
        sendBtn = mainView.findViewById(R.id.sendBtn);
        inspectBtn = mainView.findViewById(R.id.inspectBtn);
        nextPageBtn = mainView.findViewById(R.id.nextPageBtn);
        prevPageBtn = mainView.findViewById(R.id.prevPageBtn);
        pageCounter = mainView.findViewById(R.id.pageCounter);
        pageCounter.setText(String.format("Page %d / %d", currentPage, totalPages));

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

                // Generate timestamped filename
                String timestamp = new SimpleDateFormat("yyyyMMdd'T'HH:mm:ss'Z'", Locale.getDefault())
                        .format(new Date());
                File file = new File(dirPath, "cotexplorer-" + timestamp + ".txt");

                try {
                    FileWriter fw = new FileWriter(file);
                    fw.write(cotexplorerlog.getText().toString());
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
                fullLog.clear();
                cotexplorerlog.setText("");
                currentPage = 1;
                totalPages = 1;
                pageCounter.setText(String.format("Page %d / %d", currentPage, totalPages));
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

                alertBuilder.setNegativeButton("OK", (dialogInterface, i) -> {
                    cotFilter = input.getText().toString();
                    applyFilter(); // Apply the new filter to update TextView
                });

                alertBuilder.setNeutralButton("Cancel", (dialogInterface, i) -> {});

                alertBuilder.setPositiveButton("Clear", (dialogInterface, i) -> {
                    cotFilter = ""; // Clear the filter
                    applyFilter();  // Apply the empty filter to show all logs
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

        nextPageBtn.setOnClickListener(v -> {
            if (currentPage < totalPages) {
                currentPage++;
                applyFilter(); // Update the view with the new page's data
                pageCounter.setText(String.format("Page %d / %d", currentPage, totalPages));
            } else {
                Toast.makeText(pluginContext, "You are on the last page", Toast.LENGTH_SHORT).show();
            }
        });

        prevPageBtn.setOnClickListener(v -> {
            if (currentPage > 1) {
                currentPage--;
                applyFilter(); // Update the view with the new page's data
                pageCounter.setText(String.format("Page %d / %d", currentPage, totalPages));
            } else {
                Toast.makeText(pluginContext, "You are on the first page", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (action != null && action.equals(SHOW_PLUGIN)) {
            showDropDown(mainView, HALF_WIDTH, FULL_HEIGHT,
                    FULL_WIDTH, HALF_HEIGHT, false, this);
        }
    }

    private void writeLog(final String log, final String flag) {
        if (paused) return;

        new Handler(Looper.getMainLooper()).post(() -> {
            // Add the log entry to the full log list
            fullLog.add(String.format("%s: %s", flag, log));

            // Apply the current filter and update the TextView
            applyFilter();
        });
    }

    private void applyFilter() {
        SpannableStringBuilder filteredLog = new SpannableStringBuilder();
        totalPages = (int) Math.ceil((double) fullLog.size() / maxDisplayedEntries);

        // Ensure currentPage is within valid bounds
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }

        int startIdx = (currentPage - 1) * maxDisplayedEntries;
        int endIdx = Math.min(startIdx + maxDisplayedEntries, fullLog.size());

        displayedLog.clear();
        for (int i = startIdx; i < endIdx; i++) {
            String log = fullLog.get(i);
            if (cotFilter.isEmpty() || log.contains(cotFilter)) {
                if (!cotFilter.isEmpty()) {
                    // Highlight the matching part
                    int start = log.indexOf(cotFilter);
                    int end = start + cotFilter.length();

                    SpannableString spannableLog = new SpannableString(log);
                    spannableLog.setSpan(
                            new BackgroundColorSpan(0x80FFFF33), // Yellow background
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                    filteredLog.append(spannableLog);
                } else {
                    // No filter, append as plain text
                    filteredLog.append(log);
                }
                filteredLog.append("\n----------\n");
            }
        }

        cotexplorerlog.setText(filteredLog);
        pageCounter.setText(String.format("Page %d / %d", currentPage, totalPages));
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

    private void showNextPage() {
        if ((currentPage + 1) * maxDisplayedEntries < fullLog.size()) {
            currentPage++;
            applyFilter();
        }
    }

    private void showPreviousPage() {
        if (currentPage > 0) {
            currentPage--;
            applyFilter();
        }
    }

    @Override
    public void disposeImpl() {

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
        Log.i(TAG, "Sending");
        String filter;
        if (cotFilter.isEmpty())
            filter = _sharedPreference.getString("plugin_cotexplorer_type", "");
        else
            filter = cotFilter;
        if (filter.isEmpty())
            writeLog(cotEvent.toString(), "S");
        else if (filter.startsWith(cotEvent.getType()))
            writeLog(cotEvent.toString(), "S");
    }

    @Override
    public void logSend(CotEvent cotEvent, String[] strings) {
        Log.i(TAG, "Sending2");
        String filter;
        if (cotFilter.isEmpty())
            filter = _sharedPreference.getString("plugin_cotexplorer_type", "");
        else
            filter = cotFilter;
        if (filter.isEmpty())
            writeLog(cotEvent.toString(), "S");
        else if (filter.startsWith(cotEvent.getType()))
            writeLog(cotEvent.toString(), "S");
    }

    @Override
    public void logReceive(CotEvent cotEvent, String s, String s1) {
        Log.i(TAG, "Receive");
        String filter;
        if (cotFilter.isEmpty())
            filter = _sharedPreference.getString("plugin_cotexplorer_type", "");
        else
            filter = cotFilter;
        if (filter.isEmpty())
            writeLog(cotEvent.toString(), "R");
        else if (filter.startsWith(cotEvent.getType()))
            writeLog(cotEvent.toString(), "R");
    }

    @Override
    public void onClick(View view) {
        Log.i(TAG, "onClick");

    }
}
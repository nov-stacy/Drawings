package com.example.drawingsarchive;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends ComponentActivity {
    private static final int REQUEST_IMAGE = 1201;
    private static final int REQUEST_DOCUMENT_SCAN = 1202;
    private static final int REQUEST_BACKUP_FOLDER = 1301;
    private static final int REQUEST_CREATE_BACKUP = 1302;
    private static final int REQUEST_OPEN_BACKUP = 1303;
    private static final String PREFS = "drawings_archive";
    private static final String KEY_ARTWORKS = "artworks";
    private static final String KEY_MATERIALS = "materials";
    private static final String KEY_MATERIALS_FULL = "materials_full";

    private final int COLOR_BG = Color.parseColor("#FCF9F4");
    private final int COLOR_SURFACE = Color.parseColor("#FFFFFF");
    private final int COLOR_SURFACE_2 = Color.parseColor("#F1EEEA");
    private final int COLOR_PRIMARY = Color.parseColor("#C66F55");
    private final int COLOR_PRIMARY_SOFT = Color.parseColor("#C66F55");
    private final int COLOR_TEXT = Color.parseColor("#151412");
    private final int COLOR_MUTED = Color.parseColor("#77736F");
    private final int COLOR_OUTLINE = Color.parseColor("#D8D2CC");

    private final ArrayList<Artwork> artworks = new ArrayList<>();
    private final ArrayList<String> materials = new ArrayList<>();
    private final ArrayList<Artwork> visibleArtworks = new ArrayList<>();
    private final ArrayList<String> selectedMaterialFilters = new ArrayList<>();

    private SharedPreferences preferences;
    private ThumbnailLoader thumbnailLoader;
    private FrameLayout pageRoot;
    private Uri pendingImageUri;
    private String pendingImageArtworkId;
    private ImageView pendingPreview;
    private TextView pendingPreviewHint;
    private String pendingTitleDraft = "";
    private String pendingYearDraft = String.valueOf(Calendar.getInstance().get(Calendar.YEAR));
    private final ArrayList<String> selectedArtworkMaterials = new ArrayList<>();
    private String searchQuery = "";
    private String currentScreen = "home";
    private boolean restoreHomeScroll;
    private FrameLayout retainedHomeRoot;
    private GridView retainedHomeGrid;
    private GridView homeGrid;
    private Dialog artworkDialog;
    private PopupWindow artworkActionsPopup;
    private Dialog fullscreenArtworkDialog;
    private ImageView fullscreenArtworkImage;
    private TextView fullscreenArtworkCounter;
    private TextView fullscreenArtworkTitle;
    private LinearLayout artworkCard;
    private FrameLayout artworkDetailImageContainer;
    private ImageView artworkDetailBackground;
    private ImageView artworkDetailImage;
    private TextView artworkDetailTitle;
    private TextView artworkDetailMetadata;
    private TextView artworkDetailCounter;
    private Artwork displayedArtwork;
    private final ArrayList<Artwork> detailArtworks = new ArrayList<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener backupPreferenceListener =
            (values, key) -> runOnUiThread(() -> {
                if ("settings".equals(currentScreen)) showSettings();
            });

    private Bitmap editorSourceBitmap;
    private Bitmap editorOriginalBitmap;
    private ImageRetouchView editorRetouchView;
    private ImageView editorPreview;
    private Bitmap editorGeometryBitmap;
    private CropGeometry editorGeometry;
    private CropGeometry editorCropDraft;
    private ImageCropView editorCropView;
    private float editorBrightness;
    private float editorContrast = 1f;
    private int editorFilter;
    private Artwork editorTargetArtwork;
    private String editorTitleDraft = "";
    private String editorYearDraft = "";
    private final ArrayList<String> editorMaterialDraft = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            getWindow().setAttributes(attributes);
        }
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        thumbnailLoader = new ThumbnailLoader(this);
        loadData();
        if (savedInstanceState != null) {
            pendingImageArtworkId = savedInstanceState.getString("pending_image_artwork_id");
        }
        DrawingBackup.schedule(this);
        new Thread(() -> DrawingBackup.writeIfDue(this, () -> false), "drawing-backup-check").start();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (fullscreenArtworkDialog != null) {
                    fullscreenArtworkDialog.dismiss();
                    return;
                }
                if (artworkDialog != null) {
                    artworkDialog.dismiss();
                    return;
                }
                if ("materials".equals(currentScreen)) {
                    showSettings();
                    return;
                }
                if ("crop".equals(currentScreen)) {
                    leaveCropEditor(false);
                    return;
                }
                if ("retouch".equals(currentScreen)) {
                    leaveRetouchEditor(false);
                    return;
                }
                if ("editor".equals(currentScreen)) {
                    cancelImageEditor();
                    return;
                }
                if (!"home".equals(currentScreen)) {
                    showHome();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
        showHome();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("pending_image_artwork_id", pendingImageArtworkId);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        DrawingBackup.prefs(this).registerOnSharedPreferenceChangeListener(backupPreferenceListener);
    }

    @Override
    protected void onStop() {
        DrawingBackup.prefs(this).unregisterOnSharedPreferenceChangeListener(backupPreferenceListener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (editorRetouchView != null) editorRetouchView.close();
        if (thumbnailLoader != null) thumbnailLoader.close();
        if (fullscreenArtworkDialog != null) fullscreenArtworkDialog.dismiss();
        if (artworkDialog != null) artworkDialog.dismiss();
        super.onDestroy();
    }

    private void loadData() {
        materials.clear();
        boolean hasFullList = preferences.contains(KEY_MATERIALS_FULL);
        if (!hasFullList) materials.addAll(Arrays.asList("Карандаш", "Акварель", "Маркеры"));
        try {
            String stored = preferences.getString(hasFullList ? KEY_MATERIALS_FULL : KEY_MATERIALS, "[]");
            JSONArray storedMaterials = new JSONArray(stored);
            for (int i = 0; i < storedMaterials.length(); i++) {
                String material = storedMaterials.optString(i, "").trim();
                if (!material.isEmpty() && !material.equals("Другое") && !materials.contains(material)) {
                    materials.add(material);
                }
            }
        } catch (Exception ignored) {
        }

        sortMaterials();
        artworks.clear();
        boolean generatedIds = false;
        try {
            JSONArray storedArtworks = new JSONArray(preferences.getString(KEY_ARTWORKS, "[]"));
            for (int i = 0; i < storedArtworks.length(); i++) {
                JSONObject item = storedArtworks.getJSONObject(i);
                String id = item.optString("id", "").trim();
                if (id.isEmpty()) {
                    id = UUID.randomUUID().toString();
                    generatedIds = true;
                }
                Artwork artwork = new Artwork(
                        id,
                        item.optString("title", "Без названия"),
                        item.optString("year", ""),
                        item.optString("material", ""),
                        item.optString("uri", "")
                );
                artworks.add(artwork);
            }
        } catch (Exception ignored) {
        }
        ArtworkOrder.sortByYear(artworks, artwork -> artwork.year);
        if (generatedIds) saveData();
    }

    private void saveData() {
        ArtworkOrder.sortByYear(artworks, artwork -> artwork.year);
        sortMaterials();
        JSONArray artworkArray = new JSONArray();
        for (Artwork artwork : artworks) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", artwork.id);
                item.put("title", artwork.title);
                item.put("year", artwork.year);
                item.put("material", artwork.material);
                item.put("uri", artwork.uri);
                artworkArray.put(item);
            } catch (Exception ignored) {
            }
        }

        JSONArray materialArray = new JSONArray();
        for (String material : materials) {
            materialArray.put(material);
        }

        preferences.edit()
                .putString(KEY_ARTWORKS, artworkArray.toString())
                .putString(KEY_MATERIALS_FULL, materialArray.toString())
                .apply();
    }

    private void sortMaterials() {
        Collator collator = Collator.getInstance(new Locale("ru", "RU"));
        collator.setStrength(Collator.SECONDARY);
        materials.sort(collator);
    }

    private FrameLayout newPage() {
        if (fullscreenArtworkDialog != null) fullscreenArtworkDialog.dismiss();
        if (artworkDialog != null) artworkDialog.dismiss();
        pageRoot = new FrameLayout(this);
        pageRoot.setBackgroundColor(COLOR_BG);
        pageRoot.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets.getDisplayCutout() != null) {
                top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });
        setContentView(pageRoot);
        pageRoot.requestApplyInsets();
        return pageRoot;
    }

    private void showHome() {
        if (fullscreenArtworkDialog != null) fullscreenArtworkDialog.dismiss();
        if (artworkDialog != null) artworkDialog.dismiss();
        currentScreen = "home";
        if (restoreHomeScroll && retainedHomeRoot != null && retainedHomeGrid != null) {
            restoreHomeScroll = false;
            refreshVisible();
            BaseAdapter adapter = (BaseAdapter) retainedHomeGrid.getAdapter();
            pageRoot = retainedHomeRoot;
            homeGrid = retainedHomeGrid;
            setContentView(pageRoot);
            // Reattach first so the GridView observes the update and restores its click state.
            if (adapter != null) adapter.notifyDataSetChanged();
            pageRoot.requestApplyInsets();
            retainedHomeRoot = null;
            retainedHomeGrid = null;
            return;
        }
        restoreHomeScroll = false;
        retainedHomeRoot = null;
        retainedHomeGrid = null;
        FrameLayout root = newPage();
        LinearLayout content = vertical();
        content.setPadding(dp(16), dp(8), dp(16), dp(82));
        root.addView(content, match());

        TextView heading = bodyText("Мои рисунки", 25, HOME_TEXT);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(49));
        headingParams.bottomMargin = dp(8);
        content.addView(heading, headingParams);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setText(searchQuery);
        search.setHint("Поиск");
        search.setHintTextColor(HOME_MUTED);
        search.setTextColor(HOME_TEXT);
        search.setTextSize(14);
        search.setPadding(dp(13), 0, dp(13), 0);
        android.graphics.drawable.Drawable searchIcon = getDrawable(R.drawable.ic_home_search).mutate();
        searchIcon.setTint(HOME_MUTED);
        searchIcon.setBounds(0, 0, dp(17), dp(17));
        search.setCompoundDrawablesRelative(searchIcon, null, null, null);
        search.setCompoundDrawablePadding(dp(10));
        search.setBackground(rounded(HOME_SOFT, 14));
        content.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        LinearLayout controls = new LinearLayout(this);
        controls.setBaselineAligned(false);
        LinearLayout displayMode = new LinearLayout(this);
        displayMode.setBaselineAligned(false);
        displayMode.setPadding(dp(3), dp(3), dp(3), dp(3));
        displayMode.setBackground(rounded(HOME_SOFT, 13));
        Button works = homeDisplayTab("Работы", true);
        Button years = homeDisplayTab("По годам", false);
        years.setOnClickListener(v -> showYears());
        displayMode.addView(works, new LinearLayout.LayoutParams(0, dp(30), 1));
        displayMode.addView(years, new LinearLayout.LayoutParams(0, dp(30), 1));
        controls.addView(displayMode, new LinearLayout.LayoutParams(0, dp(36), 1));

        String filterTitle = "Материалы" + (selectedMaterialFilters.isEmpty()
                ? "" : " · " + selectedMaterialFilters.size());
        Button materialFilter = homeControlButton(filterTitle);
        materialFilter.setTextSize(12);
        materialFilter.setBackground(outlined(COLOR_SURFACE, HOME_OUTLINE, 13));
        android.graphics.drawable.Drawable filterIcon = getDrawable(R.drawable.ic_home_filter).mutate();
        filterIcon.setTint(HOME_TEXT);
        filterIcon.setBounds(0, 0, dp(17), dp(17));
        materialFilter.setCompoundDrawablePadding(dp(6));
        materialFilter.setCompoundDrawablesRelative(filterIcon, null, null, null);
        materialFilter.setContentDescription(materialFilterLabel());
        materialFilter.setOnClickListener(v -> showMaterialFilterDialog());
        LinearLayout.LayoutParams materialParams = new LinearLayout.LayoutParams(0, dp(36), 1);
        materialParams.leftMargin = dp(8);
        controls.addView(materialFilter, materialParams);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        controlsParams.topMargin = dp(8);
        controlsParams.bottomMargin = dp(10);
        content.addView(controls, controlsParams);

        FrameLayout collectionArea = new FrameLayout(this);
        content.addView(collectionArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        GridView grid = new GridView(this);
        homeGrid = grid;
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(10));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, dp(16));
        grid.setVerticalScrollBarEnabled(false);
        collectionArea.addView(grid, match());

        TextView empty = bodyText("Пока нет рисунков\nНажмите «＋», чтобы добавить первую работу", 15, COLOR_MUTED);
        empty.setGravity(Gravity.CENTER);
        collectionArea.addView(empty, match());
        grid.setEmptyView(empty);

        refreshVisible();
        ArtworkAdapter artworkAdapter = new ArtworkAdapter(this, visibleArtworks);
        grid.setAdapter(artworkAdapter);
        grid.setRecyclerListener(view -> thumbnailLoader.cancel(((CardHolder) view.getTag()).image));
        grid.setOnItemClickListener((parent, view, position, id) -> showArtworkDetail(visibleArtworks.get(position)));
        CollectionFastScroller fastScroller = new CollectionFastScroller(this, grid, COLOR_OUTLINE, COLOR_PRIMARY);
        collectionArea.addView(fastScroller, new FrameLayout.LayoutParams(dp(32),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        final Artwork[] draggedArtwork = {null};
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            draggedArtwork[0] = visibleArtworks.get(position);
            ClipData data = ClipData.newPlainText("artwork", draggedArtwork[0].title);
            view.startDragAndDrop(data, new View.DragShadowBuilder(view), draggedArtwork[0], 0);
            return true;
        });
        grid.setOnDragListener((view, event) -> {
            if (event.getAction() == android.view.DragEvent.ACTION_DRAG_LOCATION) {
                int targetPosition = grid.pointToPosition((int) event.getX(), (int) event.getY());
                if (draggedArtwork[0] != null && ArtworkOrder.moveWithinYear(
                        artworks, visibleArtworks, draggedArtwork[0], targetPosition,
                        artwork -> artwork.year)) {
                    artworkAdapter.notifyDataSetChanged();
                }
                return true;
            }
            if (event.getAction() == android.view.DragEvent.ACTION_DROP) {
                saveData();
                return true;
            }
            if (event.getAction() == android.view.DragEvent.ACTION_DRAG_ENDED) {
                if (draggedArtwork[0] != null) {
                    saveData();
                }
                draggedArtwork[0] = null;
                return true;
            }
            return true;
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s.toString();
                refreshVisible();
                ((ArtworkAdapter) grid.getAdapter()).notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        addBottomNavigation(root, "Коллекция");
        styleHomeNavigation(root);
    }

    // These styles apply only to the home screen; shared screens and handlers keep their styles.
    private final int HOME_TEXT = Color.parseColor("#302B26");
    private final int HOME_MUTED = Color.parseColor("#766C63");
    private final int HOME_SOFT = Color.parseColor("#EEE8E0");
    private final int HOME_OUTLINE = Color.parseColor("#E3DBD1");
    private final int HOME_ACCENT = Color.parseColor("#AC553B");

    private Button homeControlButton(String label) {
        Button button = textButton(label, false);
        button.setTextSize(13);
        button.setTextColor(HOME_TEXT);
        button.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private Button homeDisplayTab(String label, boolean selected) {
        Button button = homeControlButton(label);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setTextColor(selected ? HOME_TEXT : HOME_MUTED);
        button.setTypeface(Typeface.create(selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        button.setBackground(rounded(selected ? COLOR_SURFACE : Color.TRANSPARENT, 10));
        button.setSelected(selected);
        button.setContentDescription(label + (selected ? ", выбрано" : ""));
        return button;
    }

    private void styleHomeNavigation(FrameLayout root) {
        // Restyle the existing navigation views without replacing their click listeners.
        LinearLayout nav = (LinearLayout) root.getChildAt(root.getChildCount() - 1);
        nav.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout collection = (LinearLayout) nav.getChildAt(0);
        ImageView collectionIcon = (ImageView) collection.getChildAt(0);
        collectionIcon.setImageResource(R.drawable.ic_home_collection);
        collectionIcon.setColorFilter(HOME_ACCENT);
        ((TextView) collection.getChildAt(1)).setTextColor(HOME_ACCENT);

        LinearLayout center = (LinearLayout) nav.getChildAt(1);
        ImageView add = (ImageView) center.getChildAt(0);
        add.setImageResource(R.drawable.ic_design_plus);
        add.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        add.setPadding(dp(15), dp(15), dp(15), dp(15));
        add.setBackground(rounded(HOME_ACCENT, 18));
        add.setElevation(0);

        LinearLayout settings = (LinearLayout) nav.getChildAt(2);
        ImageView settingsIcon = (ImageView) settings.getChildAt(0);
        settingsIcon.setImageResource(R.drawable.ic_home_settings);
        settingsIcon.setColorFilter(HOME_MUTED);
        ((TextView) settings.getChildAt(1)).setTextColor(HOME_MUTED);

        View divider = new View(this);
        divider.setBackgroundColor(HOME_OUTLINE);
        FrameLayout.LayoutParams dividerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1), Gravity.BOTTOM);
        dividerParams.leftMargin = dp(16);
        dividerParams.rightMargin = dp(16);
        dividerParams.bottomMargin = dp(70);
        root.addView(divider, dividerParams);
    }

    private void refreshVisible() {
        visibleArtworks.clear();
        String query = searchQuery.toLowerCase(Locale.getDefault()).trim();
        for (Artwork artwork : artworks) {
            boolean filterMatches = selectedMaterialFilters.isEmpty();
            for (String filter : selectedMaterialFilters) {
                if (artwork.materialList().contains(filter)) {
                    filterMatches = true;
                    break;
                }
            }
            String haystack = (artwork.title + " " + artwork.year + " " + artwork.material).toLowerCase(Locale.getDefault());
            if (filterMatches && haystack.contains(query)) visibleArtworks.add(artwork);
        }
    }

    private String materialFilterLabel() {
        if (selectedMaterialFilters.isEmpty()) return "Материалы: все";
        if (selectedMaterialFilters.size() == 1) return "Материалы: " + selectedMaterialFilters.get(0);
        return "Материалы: выбрано " + selectedMaterialFilters.size();
    }

    private void showMaterialFilterDialog() {
        boolean[] checked = new boolean[materials.size()];
        ArrayList<String> draft = new ArrayList<>(selectedMaterialFilters);
        for (int i = 0; i < materials.size(); i++) checked[i] = draft.contains(materials.get(i));

        new AlertDialog.Builder(this)
                .setTitle("Материалы")
                .setMultiChoiceItems(materials.toArray(new String[0]), checked, (dialog, which, isChecked) -> {
                    String material = materials.get(which);
                    if (isChecked && !draft.contains(material)) draft.add(material);
                    if (!isChecked) draft.remove(material);
                })
                .setNegativeButton("Отмена", null)
                .setNeutralButton("Сбросить", (dialog, which) -> {
                    selectedMaterialFilters.clear();
                    showHome();
                })
                .setPositiveButton("Готово", (dialog, which) -> {
                    selectedMaterialFilters.clear();
                    selectedMaterialFilters.addAll(draft);
                    showHome();
                })
                .show();
    }

    private void showAddArtwork() {
        showAddArtwork(true);
    }

    private void showAddArtwork(boolean resetDraft) {
        currentScreen = "add";
        if (resetDraft) {
            pendingImageUri = null;
            pendingTitleDraft = "";
            pendingYearDraft = String.valueOf(Calendar.getInstance().get(Calendar.YEAR));
            selectedArtworkMaterials.clear();
            if (materials.contains("Акварель")) selectedArtworkMaterials.add("Акварель");
            else if (!materials.isEmpty()) selectedArtworkMaterials.add(materials.get(0));
        }
        keepSingleMaterial(selectedArtworkMaterials);
        FrameLayout root = newPage();
        LinearLayout page = vertical();
        page.setPadding(dp(18), dp(6), dp(18), dp(14));
        root.addView(page, match());
        page.addView(designHeader("Новая работа", R.drawable.ic_design_back,
                "Назад", v -> showHome()), lpMatchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout content = vertical();
        content.setPadding(0, dp(8), 0, dp(16));
        scroll.addView(content, lpMatchWrap());
        if (pendingImageUri == null) {
            View scan = designSourceOption(R.drawable.ic_source_scan, "Сканировать рисунок",
                    "Найти границы и выровнять");
            scan.setOnClickListener(v -> scanWithCamera());
            content.addView(scan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)));
            View gallery = designSourceOption(R.drawable.ic_source_gallery, "Выбрать фото",
                    "Добавить изображение из галереи");
            gallery.setOnClickListener(v -> chooseImage());
            LinearLayout.LayoutParams galleryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78));
            galleryParams.topMargin = dp(10);
            content.addView(gallery, galleryParams);
            pendingPreview = null;
            pendingPreviewHint = null;
        } else {
            content.addView(designPreviewSummary(pendingImageUri, "Фото выбрано", "",
                    "Заменить фото", v -> showImageSourceDialog()), lpMatchWrap());
        }
        EditText titleInput = designField("Введите название");
        titleInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleInput.setText(pendingTitleDraft);
        EditText yearInput = designField("");
        yearInput.setText(pendingYearDraft);
        yearInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        titleInput.addTextChangedListener(new DraftTextWatcher(value -> pendingTitleDraft = value));
        yearInput.addTextChangedListener(new DraftTextWatcher(value -> pendingYearDraft = value));
        content.addView(designFormField("Название", titleInput), designFormParams());
        content.addView(designFormField("Год создания", yearInput), designFormParams());
        Button material = designMaterialButton(selectedArtworkMaterials);
        material.setOnClickListener(v -> showMaterialPicker(material));
        content.addView(designFormField("Материал", material), designFormParams());

        Button save = designPrimaryButton("Сохранить работу");
        save.setEnabled(pendingImageUri != null);
        save.setAlpha(pendingImageUri == null ? .45f : 1f);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        saveParams.topMargin = dp(12);
        page.addView(save, saveParams);
        save.setOnClickListener(v -> {
            String titleValue = titleInput.getText().toString().trim();
            String yearValue = yearInput.getText().toString().trim();
            if (pendingImageUri == null) {
                Toast.makeText(this, "Сначала выберите рисунок", Toast.LENGTH_SHORT).show();
                return;
            }
            if (yearValue.isEmpty()) {
                yearInput.setError("Укажите год");
                yearInput.requestFocus();
                return;
            }
            int year;
            try { year = Integer.parseInt(yearValue); }
            catch (NumberFormatException error) { yearInput.setError("Введите год цифрами"); return; }
            if (year < 1000 || year > 2100) { yearInput.setError("Проверьте год"); return; }
            if (titleValue.isEmpty()) titleValue = "Без названия";
            if (selectedArtworkMaterials.isEmpty()) {
                Toast.makeText(this, "Выберите материал", Toast.LENGTH_SHORT).show();
                return;
            }
            ArtworkOrder.putFirstInYear(artworks,
                    new Artwork(UUID.randomUUID().toString(), titleValue, yearValue,
                            selectedArtworkMaterials.get(0), pendingImageUri.toString()),
                    artwork -> artwork.year);
            saveData();
            Toast.makeText(this, "Работа сохранена", Toast.LENGTH_SHORT).show();
            showHome();
        });
    }

    private void renderArtworkMaterialChips(LinearLayout row) {
        row.removeAllViews();
        for (String material : materials) {
            boolean selected = selectedArtworkMaterials.contains(material);
            Button option = materialChoiceChip(material, selected);
            option.setOnClickListener(v -> {
                if (selectedArtworkMaterials.contains(material)) selectedArtworkMaterials.remove(material);
                else selectedArtworkMaterials.add(material);
                renderArtworkMaterialChips(row);
            });
            row.addView(option);
        }
        Button add = materialChoiceChip("＋ Добавить", false);
        add.setOnClickListener(v -> showAddMaterialDialog(material -> {
            if (!selectedArtworkMaterials.contains(material)) selectedArtworkMaterials.add(material);
            renderArtworkMaterialChips(row);
        }));
        row.addView(add);
    }

    private void chooseImage() {
        chooseImage(null);
    }

    private void chooseImage(Artwork target) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pendingImageArtworkId = target == null ? null : target.id;
        startActivityForResult(intent, REQUEST_IMAGE);
    }

    private void showImageSourceDialog() {
        showImageSourceDialog(null);
    }

    private void showImageSourceDialog(Artwork target) {
        LinearLayout choices = vertical();
        choices.setPadding(dp(18), dp(2), dp(18), dp(4));

        TextView hint = bodyText(target == null ? "Как добавить рисунок?" : "Выберите новое фото работы", 14, COLOR_MUTED);
        LinearLayout.LayoutParams hintParams = lpMatchWrap();
        hintParams.bottomMargin = dp(14);
        choices.addView(hint, hintParams);

        View scan = imageSourceOption(
                R.drawable.ic_source_scan,
                "Сканировать",
                "Камера найдёт границы и выровняет рисунок"
        );
        choices.addView(scan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82)));

        View gallery = imageSourceOption(
                R.drawable.ic_source_gallery,
                "Выбрать фото",
                "Добавить готовое изображение из галереи"
        );
        LinearLayout.LayoutParams galleryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82));
        galleryParams.topMargin = dp(10);
        choices.addView(gallery, galleryParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(target == null ? "Добавить рисунок" : "Заменить фото")
                .setView(choices)
                .setNegativeButton("Отмена", null)
                .create();
        scan.setOnClickListener(v -> {
            dialog.dismiss();
            scanWithCamera(target);
        });
        gallery.setOnClickListener(v -> {
            dialog.dismiss();
            chooseImage(target);
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(COLOR_PRIMARY);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(rounded(COLOR_BG, 24));
            }
        });
        dialog.show();
    }

    private View imageSourceOption(int iconResource, String title, String description) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackground(rounded(COLOR_SURFACE, 17));
        row.setElevation(dp(1));
        row.setClickable(true);

        FrameLayout iconHolder = new FrameLayout(this);
        iconHolder.setBackground(rounded(COLOR_SURFACE_2, 15));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        iconHolder.addView(icon, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(iconHolder, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout copy = vertical();
        copy.setPadding(dp(13), 0, dp(7), 0);
        TextView name = bodyText(title, 16, COLOR_TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        copy.addView(name, lpMatchWrap());
        TextView detail = bodyText(description, 12, COLOR_MUTED);
        detail.setMaxLines(2);
        LinearLayout.LayoutParams detailParams = lpMatchWrap();
        detailParams.topMargin = dp(3);
        copy.addView(detail, detailParams);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = bodyText("›", 28, COLOR_PRIMARY);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(48)));
        return row;
    }

    private void scanWithCamera() {
        scanWithCamera(null);
    }

    private void scanWithCamera(Artwork target) {
        GmsDocumentScannerOptions options = new GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .setPageLimit(1)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
                .build();
        Toast.makeText(this, "Готовим сканер…", Toast.LENGTH_SHORT).show();
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
                .addOnSuccessListener(this, sender -> {
                    try {
                        pendingImageArtworkId = target == null ? null : target.id;
                        startIntentSenderForResult(sender, REQUEST_DOCUMENT_SCAN, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException error) {
                        pendingImageArtworkId = null;
                        showScannerUnavailable(target);
                    }
                })
                .addOnFailureListener(this, error -> showScannerUnavailable(target));
    }

    private void showScannerUnavailable(Artwork target) {
        new AlertDialog.Builder(this)
                .setTitle("Сканер пока недоступен")
                .setMessage("Для первого запуска подключитесь к интернету и проверьте обновления сервисов Google Play. Можно повторить попытку или выбрать готовое фото.")
                .setNegativeButton("Закрыть", null)
                .setPositiveButton("Выбрать фото", (dialog, which) -> chooseImage(target))
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        String targetId = null;
        if (requestCode == REQUEST_IMAGE || requestCode == REQUEST_DOCUMENT_SCAN) {
            targetId = pendingImageArtworkId;
            pendingImageArtworkId = null;
        }
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQUEST_BACKUP_FOLDER && data != null && data.getData() != null) {
            try {
                DrawingBackup.choose(this, data.getData());
                Toast.makeText(this, "Ежедневная копия включена", Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(this, "Нет доступа к папке. Выберите другую", Toast.LENGTH_LONG).show();
            }
            showSettings();
        } else if (requestCode == REQUEST_CREATE_BACKUP && data != null && data.getData() != null) {
            exportBackup(data.getData());
        } else if (requestCode == REQUEST_OPEN_BACKUP && data != null && data.getData() != null) {
            inspectBackup(data.getData());
        } else if (requestCode == REQUEST_IMAGE && data != null && data.getData() != null) {
            Uri selected = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            editSelectedImage(selected, targetId);
        } else if (requestCode == REQUEST_DOCUMENT_SCAN) {
            GmsDocumentScanningResult scanned = GmsDocumentScanningResult.fromActivityResultIntent(data);
            if (scanned == null || scanned.getPages() == null || scanned.getPages().size() != 1) {
                Toast.makeText(this, "Не удалось получить снимок. Попробуйте ещё раз", Toast.LENGTH_SHORT).show();
                return;
            }
            editSelectedImage(scanned.getPages().get(0).getImageUri(), targetId);
        }
    }

    private void editSelectedImage(Uri source, String targetId) {
        editorTargetArtwork = null;
        if (targetId != null) {
            for (Artwork artwork : artworks) {
                if (artwork.id.equals(targetId)) {
                    editorTargetArtwork = artwork;
                    break;
                }
            }
            if (editorTargetArtwork == null) {
                Toast.makeText(this, "Работа больше недоступна", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        showImageEditor(source, true);
        if (!"editor".equals(currentScreen)) editorTargetArtwork = null;
    }

    private void showMaterialPicker(Button target) {
        showSingleMaterialPicker(selectedArtworkMaterials, () -> updateDesignMaterialButton(target, selectedArtworkMaterials));
    }

    private String selectedMaterialsLabel() {
        if (selectedArtworkMaterials.isEmpty()) return "Материалы: выберите";
        if (selectedArtworkMaterials.size() == 1) return "Материал: " + selectedArtworkMaterials.get(0);
        return "Материалы: " + joinMaterials(selectedArtworkMaterials);
    }

    private String joinMaterials(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(" · ");
            result.append(value);
        }
        return result.toString();
    }

    private void showAddMaterialDialog(MaterialAddedListener listener) {
        EditText input = designField("Например, пастель");
        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(dp(20), dp(6), dp(20), 0);
        holder.addView(input, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        new AlertDialog.Builder(this)
                .setTitle("Новый материал")
                .setView(holder)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Добавить", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) {
                        Toast.makeText(this, "Введите название материала", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!materials.contains(value)) materials.add(value);
                    saveData();
                    listener.onAdded(value);
                })
                .show();
    }

    private void showImageEditor(Uri sourceUri) {
        showImageEditor(sourceUri, editorTargetArtwork == null);
    }

    private void showImageEditor(Uri sourceUri, boolean imageMode) {
        Bitmap decoded = decodeBitmap(sourceUri, 2400);
        if (decoded == null) {
            Toast.makeText(this, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show();
            return;
        }
        editorSourceBitmap = decoded;
        editorOriginalBitmap = decoded;
        editorGeometry = new CropGeometry();
        editorGeometryBitmap = null;
        editorCropDraft = null;
        editorCropView = null;
        editorBrightness = 0f;
        editorContrast = 1f;
        editorFilter = 0;
        editorTitleDraft = editorTargetArtwork == null ? pendingTitleDraft : editorTargetArtwork.title;
        editorYearDraft = editorTargetArtwork == null ? pendingYearDraft : editorTargetArtwork.year;
        editorMaterialDraft.clear();
        if (editorTargetArtwork == null) editorMaterialDraft.addAll(selectedArtworkMaterials);
        else editorMaterialDraft.addAll(editorTargetArtwork.materialList());
        if (editorTargetArtwork == null) keepSingleMaterial(editorMaterialDraft);
        showEditorScreen(imageMode);
    }

    // Presentation shared only by the editor and its crop screen.
    private final int EDITOR_TEXT = Color.parseColor("#302B26");
    private final int EDITOR_MUTED = Color.parseColor("#766C63");
    private final int EDITOR_SOFT = Color.parseColor("#EEE8E0");
    private final int EDITOR_OUTLINE = Color.parseColor("#E3DBD1");
    private final int EDITOR_ACCENT = Color.parseColor("#AC553B");

    private LinearLayout editorPageHeader(String title, String backDescription, View.OnClickListener backAction) {
        LinearLayout header = new LinearLayout(this);
        header.setBaselineAligned(false);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = designIcon(R.drawable.ic_design_back, backDescription, EDITOR_TEXT);
        back.setOnClickListener(backAction);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(44), dp(49));
        backParams.leftMargin = dp(-10);
        header.addView(back, backParams);
        TextView heading = bodyText(title, 25, EDITOR_TEXT);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(49), 1);
        titleParams.leftMargin = dp(9);
        header.addView(heading, titleParams);
        return header;
    }

    private Button editorBaseButton(String label) {
        Button button = textButton(label, false);
        button.setTextSize(14);
        button.setTextColor(EDITOR_TEXT);
        button.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setPadding(dp(13), 0, dp(13), 0);
        return button;
    }

    private Button editorOutlineButton(String label) {
        Button button = editorBaseButton(label);
        button.setBackground(outlined(COLOR_SURFACE, EDITOR_OUTLINE, 13));
        return button;
    }

    private Button editorToolButton(String label, int resource) {
        Button button = editorOutlineButton(label);
        button.setTextSize(12);
        button.setSingleLine(true);
        button.setPadding(dp(4), 0, dp(4), 0);
        android.graphics.drawable.Drawable icon = getDrawable(resource).mutate();
        icon.setTint(EDITOR_TEXT);
        icon.setBounds(0, 0, dp(14), dp(14));
        button.setCompoundDrawablePadding(dp(4));
        button.setCompoundDrawablesRelative(icon, null, null, null);
        return button;
    }

    private Button editorPrimaryButton(String label) {
        Button button = editorBaseButton(label);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(Color.WHITE);
        button.setBackground(rounded(EDITOR_ACCENT, 13));
        return button;
    }

    private Button editorSegmentButton(String label, boolean selected) {
        Button button = editorBaseButton(label);
        button.setTextSize(13);
        button.setPadding(dp(12), 0, dp(12), 0);
        styleEditorSegment(button, selected);
        return button;
    }

    private void styleEditorSegment(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(selected ? EDITOR_TEXT : EDITOR_MUTED);
        button.setTypeface(Typeface.create(selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        button.setBackground(rounded(selected ? COLOR_SURFACE : Color.TRANSPARENT, 10));
        button.setContentDescription(button.getText() + (selected ? ", выбран" : ", не выбран"));
    }

    private SeekBar editorSeekBar() {
        SeekBar bar = designSeekBar();
        bar.setPadding(dp(12), 0, dp(12), 0);
        bar.setProgressTintList(ColorStateList.valueOf(EDITOR_ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(EDITOR_ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(EDITOR_OUTLINE));
        return bar;
    }

    private TextView editorAdjustmentHeader(LinearLayout content, String label, int value) {
        LinearLayout row = new LinearLayout(this);
        row.setBaselineAligned(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = bodyText(label, 14, EDITOR_TEXT);
        row.addView(caption, new LinearLayout.LayoutParams(0, dp(24), 1));
        TextView number = bodyText(designSignedNumber(value), 12, EDITOR_MUTED);
        number.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(number, new LinearLayout.LayoutParams(dp(54), dp(24)));
        LinearLayout.LayoutParams params = lpMatchWrap();
        params.topMargin = dp(22);
        params.bottomMargin = dp(8);
        content.addView(row, params);
        return number;
    }

    private EditText editorInfoField(String hint) {
        EditText field = designField(hint);
        field.setTextSize(16);
        field.setTextColor(EDITOR_TEXT);
        field.setHintTextColor(EDITOR_MUTED);
        field.setBackground(outlined(COLOR_SURFACE, EDITOR_OUTLINE, 13));
        return field;
    }

    private View editorFormField(String label, View input) {
        LinearLayout block = vertical();
        TextView caption = bodyText(label, 14, EDITOR_TEXT);
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setLabelFor(input.getId() == View.NO_ID ? assignDesignId(input) : input.getId());
        block.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));
        block.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        return block;
    }

    private LinearLayout.LayoutParams editorFormParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        params.topMargin = dp(24);
        return params;
    }

    private void styleEditorMaterialButton(Button button) {
        button.setTextSize(16);
        button.setTextColor(editorMaterialDraft.isEmpty() ? EDITOR_MUTED : EDITOR_TEXT);
        button.setBackground(outlined(COLOR_SURFACE, EDITOR_OUTLINE, 13));
        android.graphics.drawable.Drawable arrow = button.getCompoundDrawablesRelative()[2];
        if (arrow != null) arrow.setTint(EDITOR_MUTED);
    }

    private void showEditorScreen(boolean imageMode) {
        currentScreen = "editor";
        FrameLayout root = newPage();
        LinearLayout page = vertical();
        page.setPadding(dp(20), dp(8), dp(20), dp(8));
        root.addView(page, match());
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(49));
        headerParams.bottomMargin = dp(23);
        page.addView(editorPageHeader("Редактор", "Назад", v -> cancelImageEditor()), headerParams);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setBaselineAligned(false);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(rounded(EDITOR_SOFT, 13));
        Button imageTab = editorSegmentButton("Изображение", imageMode);
        Button infoTab = editorSegmentButton("Информация", !imageMode);
        tabs.addView(imageTab, new LinearLayout.LayoutParams(0, dp(40), 1));
        LinearLayout.LayoutParams infoTabParams = new LinearLayout.LayoutParams(0, dp(40), 1);
        infoTabParams.leftMargin = dp(3);
        tabs.addView(infoTab, infoTabParams);
        page.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        imageTab.setOnClickListener(v -> showEditorScreen(true));
        infoTab.setOnClickListener(v -> showEditorScreen(false));
        FrameLayout panel = new FrameLayout(this);
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        panelParams.topMargin = dp(18);
        page.addView(panel, panelParams);
        if (imageMode) renderImageEditorPanel(panel);
        else renderInfoEditorPanel(panel);
        LinearLayout actions = new LinearLayout(this);
        actions.setBaselineAligned(false);
        Button cancel = editorOutlineButton("Отмена");
        cancel.setOnClickListener(v -> cancelImageEditor());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button done = editorPrimaryButton("Сохранить");
        done.setOnClickListener(v -> saveEditorResult());
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        doneParams.leftMargin = dp(9);
        actions.addView(done, doneParams);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        actionsParams.topMargin = dp(14);
        page.addView(actions, actionsParams);
    }

    private void renderImageEditorPanel(FrameLayout panel) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = vertical();
        content.setPadding(0, 0, 0, dp(8));
        scroll.addView(content, lpMatchWrap());
        panel.addView(scroll, match());
        editorPreview = new ImageView(this);
        editorPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        editorPreview.setBackground(rounded(EDITOR_SOFT, 17));
        editorPreview.setClipToOutline(true);
        editorPreview.setContentDescription("Предпросмотр рисунка");
        // A compact preview keeps all adjustments visible on the connected phone.
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(258));
        content.addView(editorPreview, previewParams);
        // Keep the mockup's maximum preview size, shrinking it on shorter screens so reset stays visible.
        scroll.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int available = bottom - top;
            int overflow = content.getMeasuredHeight() - available;
            if (available > 0 && overflow > 0) {
                int height = Math.max(dp(180), previewParams.height - overflow);
                if (height < previewParams.height) {
                    previewParams.height = height;
                    content.getChildAt(0).setLayoutParams(previewParams);
                }
            }
        });
        LinearLayout geometry = new LinearLayout(this);
        geometry.setBaselineAligned(false);
        LinearLayout.LayoutParams geometryParams = lpMatchWrap();
        geometryParams.topMargin = dp(12);
        content.addView(geometry, geometryParams);
        Button crop = editorToolButton("Обрезать", R.drawable.ic_design_crop);
        geometry.addView(crop, new LinearLayout.LayoutParams(0, dp(44), 1));
        crop.setOnClickListener(v -> showCropEditor());
        Button rotate = editorToolButton("Повернуть", R.drawable.ic_design_rotate);
        LinearLayout.LayoutParams rotateParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        rotateParams.leftMargin = dp(6);
        geometry.addView(rotate, rotateParams);
        rotate.setOnClickListener(v -> {
            editorGeometry.rotateClockwise();
            editorGeometryBitmap = null;
            refreshEditorPreview();
        });
        Button erase = editorToolButton("Ластик", R.drawable.ic_design_eraser);
        erase.setTextColor(EDITOR_ACCENT);
        erase.getCompoundDrawablesRelative()[0].setTint(EDITOR_ACCENT);
        erase.setBackground(outlined(COLOR_SURFACE, EDITOR_ACCENT, 13));
        erase.setContentDescription("Магический ластик");
        LinearLayout.LayoutParams eraseParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        eraseParams.leftMargin = dp(6);
        geometry.addView(erase, eraseParams);
        erase.setOnClickListener(v -> showRetouchEditor());
        TextView filtersTitle = bodyText("Фильтр", 14, EDITOR_TEXT);
        LinearLayout.LayoutParams filtersTitleParams = lpMatchWrap();
        filtersTitleParams.topMargin = dp(24);
        filtersTitleParams.bottomMargin = dp(9);
        content.addView(filtersTitle, filtersTitleParams);
        LinearLayout filters = new LinearLayout(this);
        filters.setBaselineAligned(false);
        filters.setPadding(dp(3), dp(3), dp(3), dp(3));
        filters.setBackground(rounded(EDITOR_SOFT, 13));
        String[] filterNames = {"Оригинал", "Ч/б", "Тёплый"};
        for (int i = 0; i < filterNames.length; i++) {
            final int mode = i;
            Button filter = editorSegmentButton(filterNames[i], editorFilter == i);
            filter.setPadding(dp(7), 0, dp(7), 0);
            filter.setSelected(editorFilter == i);
            filter.setContentDescription(filterNames[i] + (editorFilter == i ? ", выбран" : ", не выбран"));
            LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(0, dp(32), 1);
            if (i > 0) filterParams.leftMargin = dp(3);
            filters.addView(filter, filterParams);
            filter.setOnClickListener(v -> { editorFilter = mode; renderImageEditorPanelReplacing(panel); });
        }
        content.addView(filters, lpMatchWrap());
        TextView brightnessValue = editorAdjustmentHeader(content, "Яркость", Math.round(editorBrightness / 1.15f));
        SeekBar brightness = editorSeekBar();
        brightness.setProgress(Math.round(editorBrightness / 1.15f + 100));
        brightness.setContentDescription("Яркость");
        content.addView(brightness, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
        brightness.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                editorBrightness = (progress - 100) * 1.15f;
                brightnessValue.setText(designSignedNumber(progress - 100));
                updateEditorColorFilter();
            }
        });
        TextView contrastValue = editorAdjustmentHeader(content, "Контраст", Math.round((editorContrast - 1f) * 100));
        SeekBar contrast = editorSeekBar();
        // Keep the same contrast range, with neutral contrast at the middle of the control.
        contrast.setProgress(Math.round(editorContrast <= 1f
                ? 100f + (editorContrast - 1f) / .0055f : 100f + (editorContrast - 1f) / .0155f));
        contrast.setContentDescription("Контраст");
        content.addView(contrast, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
        contrast.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                editorContrast = progress <= 100 ? 1f + (progress - 100) * .0055f : 1f + (progress - 100) * .0155f;
                contrastValue.setText(designSignedNumber(Math.round((editorContrast - 1f) * 100)));
                updateEditorColorFilter();
            }
        });
        Button reset = editorBaseButton("Сбросить изменения");
        reset.setTextSize(13);
        reset.setTextColor(EDITOR_ACCENT);
        reset.setPadding(0, 0, 0, 0);
        reset.setBackground(rounded(Color.TRANSPARENT, 10));
        designButtonIcon(reset, R.drawable.ic_design_reset, EDITOR_ACCENT);
        reset.setOnClickListener(v -> {
            editorBrightness = 0f;
            editorContrast = 1f;
            editorFilter = 0;
            editorGeometry = new CropGeometry();
            editorSourceBitmap = editorOriginalBitmap;
            editorGeometryBitmap = null;
            renderImageEditorPanelReplacing(panel);
        });
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        resetParams.topMargin = dp(4);
        content.addView(reset, resetParams);
        refreshEditorPreview();
    }

    private void renderImageEditorPanelReplacing(FrameLayout panel) {
        panel.removeAllViews();
        renderImageEditorPanel(panel);
    }

    private void showRetouchEditor() {
        ImageRetouchView view;
        try {
            view = new ImageRetouchView(this, editorSourceBitmap, editorGeometry,
                    new ColorMatrixColorFilter(editorColorMatrix()));
        } catch (RuntimeException | OutOfMemoryError error) {
            Toast.makeText(this, "Не удалось открыть ластик. Попробуйте фото поменьше.", Toast.LENGTH_LONG).show();
            return;
        }
        editorPreview = null;
        editorGeometryBitmap = null;
        editorRetouchView = view;
        currentScreen = "retouch";
        FrameLayout root = newPage();
        LinearLayout page = vertical();
        page.setPadding(dp(20), dp(8), dp(20), dp(8));
        root.addView(page, match());
        LinearLayout header = editorPageHeader("Магический ластик", "Назад в редактор", v -> leaveRetouchEditor(false));
        ((TextView) header.getChildAt(1)).setTextSize(22);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(49));
        headerParams.bottomMargin = dp(15);
        page.addView(header, headerParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = vertical();
        scroll.addView(body, lpMatchWrap());
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout modes = new LinearLayout(this);
        modes.setBaselineAligned(false);
        modes.setPadding(dp(4), dp(4), dp(4), dp(4));
        modes.setBackground(rounded(EDITOR_SOFT, 13));
        Button brush = editorSegmentButton("Ластик", true);
        Button move = editorSegmentButton("Перемещение", false);
        modes.addView(brush, new LinearLayout.LayoutParams(0, dp(40), 1));
        modes.addView(move, new LinearLayout.LayoutParams(0, dp(40), 1));
        body.addView(modes, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        view.setBackground(rounded(EDITOR_SOFT, 17));
        view.setClipToOutline(true);
        view.setMinimumHeight(dp(220));
        LinearLayout.LayoutParams photoParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        photoParams.topMargin = dp(16);
        photoParams.bottomMargin = dp(12);
        body.addView(view, photoParams);

        LinearLayout history = new LinearLayout(this);
        history.setBaselineAligned(false);
        Button undo = editorOutlineButton("Отменить");
        Button redo = editorOutlineButton("Повторить");
        Button compare = editorOutlineButton("До / после");
        for (Button button : new Button[]{undo, redo, compare}) {
            button.setTextSize(12); button.setPadding(dp(4), 0, dp(4), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
            if (button != undo) params.leftMargin = dp(6);
            history.addView(button, params);
        }
        undo.setOnClickListener(v -> view.undo());
        redo.setOnClickListener(v -> view.redo());
        compare.setOnClickListener(v -> view.setBefore(!view.isBefore()));
        body.addView(history, lpMatchWrap());

        TextView brushValue = editorAdjustmentHeader(body, "Размер кисти", 24);
        brushValue.setText("24");
        SeekBar brushSize = editorSeekBar();
        brushSize.setMax(52); brushSize.setProgress(16);
        brushSize.setContentDescription("Размер кисти ластика");
        body.addView(brushSize, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
        brushSize.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                brushValue.setText(String.valueOf(progress + 8)); view.setBrushSize(progress + 8);
            }
        });
        TextView zoomValue = editorAdjustmentHeader(body, "Приближение", 1);
        zoomValue.setText("1,0×");
        SeekBar zoom = editorSeekBar();
        zoom.setMax(200); zoom.setProgress(0);
        zoom.setContentDescription("Приближение для точной ретуши");
        body.addView(zoom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
        zoom.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) view.setZoom(1 + progress / 100f);
            }
        });
        TextView hint = bodyText("Проведите по небольшому пятну, чтобы убрать его.", 12, EDITOR_MUTED);
        hint.setMinLines(2); hint.setMaxLines(2);
        LinearLayout.LayoutParams hintParams = lpMatchWrap();
        hintParams.topMargin = dp(12);
        body.addView(hint, hintParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setBaselineAligned(false);
        Button cancel = editorOutlineButton("Отмена");
        cancel.setOnClickListener(v -> leaveRetouchEditor(false));
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button apply = editorPrimaryButton("Применить");
        apply.setOnClickListener(v -> leaveRetouchEditor(true));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        applyParams.leftMargin = dp(9);
        actions.addView(apply, applyParams);
        LinearLayout.LayoutParams actionsParams = lpMatchWrap();
        actionsParams.topMargin = dp(16);
        page.addView(actions, actionsParams);
        Runnable refresh = () -> {
            boolean available = !view.isBusy();
            undo.setEnabled(view.canUndo()); redo.setEnabled(view.canRedo());
            undo.setAlpha(view.canUndo() ? 1 : .4f); redo.setAlpha(view.canRedo() ? 1 : .4f);
            apply.setEnabled(available); apply.setAlpha(available ? 1 : .5f);
            brush.setEnabled(available); move.setEnabled(available); compare.setEnabled(available);
            brushSize.setEnabled(available); zoom.setEnabled(available);
            compare.setText(view.isBefore() ? "После" : "До / после");
            compare.setSelected(view.isBefore());
            hint.setText(view.isBusy() ? "Убираем пятно…" : view.isBefore() ? "Оригинал до ретуши. Нажмите «После» для сравнения."
                    : move.isSelected() ? "Перемещайте фото. Двумя пальцами можно приблизить детали."
                    : "Проведите по небольшому пятну, чтобы убрать его.");
            zoomValue.setText(String.format(Locale.getDefault(), "%.1f×", view.getZoom()));
            zoom.setProgress(Math.round((view.getZoom() - 1) * 100));
        };
        view.setOnChangeListener(refresh);
        brush.setOnClickListener(v -> { view.setMoving(false); styleEditorSegment(brush, true); styleEditorSegment(move, false); refresh.run(); });
        move.setOnClickListener(v -> { view.setMoving(true); styleEditorSegment(brush, false); styleEditorSegment(move, true); refresh.run(); });
        refresh.run();
    }

    private void leaveRetouchEditor(boolean apply) {
        if (editorRetouchView == null) return;
        if (apply && editorRetouchView.isBusy()) return;
        if (apply) { editorSourceBitmap = editorRetouchView.takeResult(); editorGeometryBitmap = null; }
        editorRetouchView.close();
        editorRetouchView = null;
        showEditorScreen(true);
    }

    private void showCropEditor() {
        editorPreview = null;
        editorCropDraft = editorGeometry.copy();
        currentScreen = "crop";
        FrameLayout root = newPage();
        LinearLayout page = vertical();
        page.setPadding(dp(20), dp(8), dp(20), dp(8));
        root.addView(page, match());

        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(49));
        headerParams.bottomMargin = dp(23);
        page.addView(editorPageHeader("Обрезка", "Назад в редактор", v -> leaveCropEditor(false)), headerParams);

        LinearLayout modes = new LinearLayout(this);
        modes.setBaselineAligned(false);
        modes.setPadding(dp(4), dp(4), dp(4), dp(4));
        modes.setBackground(rounded(EDITOR_SOFT, 13));
        Button free = editorSegmentButton("Свободная рамка", true);
        Button corners = editorSegmentButton("Четыре угла", false);
        free.setPadding(dp(7), 0, dp(7), 0);
        corners.setPadding(dp(7), 0, dp(7), 0);
        modes.addView(free, new LinearLayout.LayoutParams(0, dp(40), 1));
        LinearLayout.LayoutParams cornersParams = new LinearLayout.LayoutParams(0, dp(40), 1);
        cornersParams.leftMargin = dp(3);
        modes.addView(corners, cornersParams);
        page.addView(modes, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        editorCropView = new ImageCropView(this, editorSourceBitmap, editorCropDraft,
                new ColorMatrixColorFilter(editorColorMatrix()));
        editorCropView.setBackground(rounded(EDITOR_SOFT, 17));
        editorCropView.setClipToOutline(true);
        LinearLayout.LayoutParams photoParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        photoParams.topMargin = dp(20);
        photoParams.bottomMargin = dp(18);
        page.addView(editorCropView, photoParams);

        LinearLayout formats = new LinearLayout(this);
        formats.setBaselineAligned(false);
        formats.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout ratiosPanel = new LinearLayout(this);
        ratiosPanel.setBaselineAligned(false);
        ratiosPanel.setPadding(dp(4), dp(4), dp(4), dp(4));
        ratiosPanel.setBackground(rounded(EDITOR_SOFT, 13));
        Button unrestricted = editorSegmentButton("Свободно", true);
        Button square = editorSegmentButton("1:1", false);
        Button portrait = editorSegmentButton("4:5", false);
        ImageView turn = designIcon(R.drawable.ic_design_rotate, "Повернуть на 90 градусов", EDITOR_TEXT);
        turn.setBackground(outlined(COLOR_SURFACE, EDITOR_OUTLINE, 13));
        turn.setClickable(true);
        Button[] formatButtons = {unrestricted, square, portrait};
        for (Button button : formatButtons) {
            button.setPadding(dp(4), 0, dp(4), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1);
            if (button != unrestricted) params.leftMargin = dp(3);
            ratiosPanel.addView(button, params);
        }
        formats.addView(ratiosPanel, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams turnParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        turnParams.leftMargin = dp(9);
        formats.addView(turn, turnParams);
        page.addView(formats, lpMatchWrap());

        LinearLayout dimensions = new LinearLayout(this);
        dimensions.setGravity(Gravity.CENTER_VERTICAL);
        TextView sizeLabel = bodyText("Размер кадра", 12, EDITOR_MUTED);
        sizeLabel.setGravity(Gravity.CENTER_VERTICAL);
        dimensions.addView(sizeLabel, new LinearLayout.LayoutParams(0, dp(24), 1));
        TextView size = bodyText("", 12, EDITOR_MUTED);
        size.setGravity(Gravity.CENTER_VERTICAL);
        dimensions.addView(size, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)));
        LinearLayout.LayoutParams dimensionsParams = lpMatchWrap();
        dimensionsParams.topMargin = dp(14);
        page.addView(dimensions, dimensionsParams);

        LinearLayout zoomHeading = new LinearLayout(this);
        zoomHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView zoomLabel = bodyText("Приближение", 14, EDITOR_TEXT);
        zoomLabel.setGravity(Gravity.CENTER_VERTICAL);
        zoomHeading.addView(zoomLabel, new LinearLayout.LayoutParams(0, dp(24), 1));
        TextView zoomValue = bodyText("1,0×", 12, EDITOR_MUTED);
        zoomValue.setGravity(Gravity.CENTER_VERTICAL);
        zoomHeading.addView(zoomValue, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)));
        LinearLayout.LayoutParams zoomHeadingParams = lpMatchWrap();
        zoomHeadingParams.topMargin = dp(22);
        zoomHeadingParams.bottomMargin = dp(8);
        page.addView(zoomHeading, zoomHeadingParams);
        SeekBar zoom = editorSeekBar();
        zoom.setContentDescription("Приближение для точной обрезки");
        page.addView(zoom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
        zoom.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || editorCropView == null) return;
                editorCropView.setZoom(1 + progress / 100f);
                zoomValue.setText(String.format(Locale.getDefault(), "%.1f×", editorCropView.getZoom()));
            }
        });

        TextView cropHint = bodyText("", 12, EDITOR_MUTED);
        cropHint.setMaxLines(2);
        LinearLayout.LayoutParams hintParams = lpMatchWrap();
        hintParams.topMargin = dp(14);
        page.addView(cropHint, hintParams);

        Runnable refresh = () -> {
            if (editorCropDraft == null || editorCropView == null) return;
            free.setSelected(editorCropDraft.mode == CropGeometry.FREE);
            corners.setSelected(editorCropDraft.mode == CropGeometry.CORNERS);
            styleEditorSegment(free, free.isSelected());
            styleEditorSegment(corners, corners.isSelected());
            cropHint.setText(editorCropDraft.mode == CropGeometry.CORNERS
                    ? "Совместите четыре угла рамки с углами рисунка."
                    : "Переместите границы рамки, чтобы выбрать кадр.");
            unrestricted.setText(editorCropDraft.mode == CropGeometry.CORNERS ? "По рисунку" : "Свободно");
            portrait.setText(editorCropDraft.aspect > 1.01f ? "5:4" : "4:5");
            float[] ratios = {0, 1, .8f};
            for (int i = 0; i < 3; i++) {
                boolean selected = i == 2 ? editorCropDraft.aspect != 0 && Math.abs(editorCropDraft.aspect - 1) > .01f
                        : Math.abs(editorCropDraft.aspect - ratios[i]) < .01f;
                formatButtons[i].setSelected(selected);
                styleEditorSegment(formatButtons[i], selected);
            }
            int[] output = editorCropDraft.dimensions(editorSourceBitmap.getWidth(), editorSourceBitmap.getHeight());
            size.setText(output[0] + " × " + output[1]);
            zoom.setProgress(Math.round((editorCropView.getZoom() - 1) * 100));
            zoomValue.setText(String.format(Locale.getDefault(), "%.1f×", editorCropView.getZoom()));
        };
        editorCropView.setOnChangeListener(refresh);
        free.setOnClickListener(v -> { editorCropDraft.setMode(CropGeometry.FREE); editorCropView.geometryChanged(false); });
        corners.setOnClickListener(v -> { editorCropDraft.setMode(CropGeometry.CORNERS); editorCropView.geometryChanged(false); });
        float[] ratios = {0, 1, .8f};
        for (int i = 0; i < 3; i++) {
            final float ratio = ratios[i];
            formatButtons[i].setOnClickListener(v -> {
                editorCropDraft.setAspect(ratio, editorCropDraft.imageAspect(editorSourceBitmap.getWidth(), editorSourceBitmap.getHeight()));
                editorCropView.geometryChanged(false);
            });
        }
        turn.setOnClickListener(v -> { editorCropDraft.rotateClockwise(); editorCropView.geometryChanged(true); });

        LinearLayout actions = new LinearLayout(this);
        actions.setBaselineAligned(false);
        Button cancel = editorOutlineButton("Отмена");
        cancel.setOnClickListener(v -> leaveCropEditor(false));
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button apply = editorPrimaryButton("Применить");
        apply.setOnClickListener(v -> leaveCropEditor(true));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        applyParams.leftMargin = dp(9);
        actions.addView(apply, applyParams);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        actionsParams.topMargin = dp(20);
        page.addView(actions, actionsParams);
        editorCropView.geometryChanged(false);
    }

    private void leaveCropEditor(boolean apply) {
        if (apply) {
            editorGeometry = editorCropDraft.copy();
            editorGeometryBitmap = null;
        }
        editorCropDraft = null;
        editorCropView = null;
        showEditorScreen(true);
    }

    private void renderInfoEditorPanel(FrameLayout panel) {
        editorPreview = null;
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = vertical();
        content.setPadding(0, 0, 0, dp(16));
        scroll.addView(content, lpMatchWrap());
        panel.addView(scroll, match());
        LinearLayout summary = designCard();
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(dp(14), dp(14), dp(14), dp(14));
        summary.setBackground(outlined(COLOR_SURFACE, EDITOR_OUTLINE, 17));
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackground(rounded(EDITOR_SOFT, 11));
        preview.setClipToOutline(true);
        preview.setImageBitmap(editorImageGeometry());
        preview.setColorFilter(new ColorMatrixColorFilter(editorColorMatrix()));
        preview.setContentDescription("Миниатюра рисунка");
        summary.addView(preview, new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout summaryText = vertical();
        summaryText.setPadding(dp(14), 0, 0, 0);
        TextView name = bodyText(editorTitleDraft.isEmpty() ? "Без названия" : editorTitleDraft, 14, EDITOR_TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setMaxLines(2);
        summaryText.addView(name, lpMatchWrap());
        TextView metadata = bodyText(editorYearDraft + (editorMaterialDraft.isEmpty() ? "" : " · " + editorMaterialDraft.get(0)), 12, EDITOR_MUTED);
        metadata.setMaxLines(2);
        LinearLayout.LayoutParams metaParams = lpMatchWrap();
        metaParams.topMargin = dp(4);
        summaryText.addView(metadata, metaParams);
        summary.addView(summaryText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        content.addView(summary, lpMatchWrap());
        EditText title = editorInfoField("Введите название");
        title.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        title.setText(editorTitleDraft);
        title.addTextChangedListener(new DraftTextWatcher(value -> editorTitleDraft = value));
        content.addView(editorFormField("Название", title), editorFormParams());
        EditText year = editorInfoField("");
        year.setInputType(InputType.TYPE_CLASS_NUMBER);
        year.setText(editorYearDraft);
        year.addTextChangedListener(new DraftTextWatcher(value -> editorYearDraft = value));
        content.addView(editorFormField("Год создания", year), editorFormParams());
        Button material = designMaterialButton(editorMaterialDraft);
        styleEditorMaterialButton(material);
        material.setOnClickListener(v -> showEditorMaterialPicker(material));
        content.addView(editorFormField("Материал", material), editorFormParams());
    }

    private String editorMaterialLabel() {
        return editorMaterialDraft.isEmpty() ? "Выберите материал   ▾" : joinMaterials(editorMaterialDraft) + "   ▾";
    }

    private void showEditorMaterialPicker(Button target) {
        showSingleMaterialPicker(editorMaterialDraft, () -> {
            updateDesignMaterialButton(target, editorMaterialDraft);
            styleEditorMaterialButton(target);
        });
    }

    private void cancelImageEditor() {
        Artwork target = editorTargetArtwork;
        clearEditorState();
        if (target == null) showAddArtwork(false);
        else showArtworkDetail(target);
    }

    private void clearEditorState() {
        if (editorRetouchView != null) editorRetouchView.close();
        editorRetouchView = null;
        editorPreview = null;
        editorSourceBitmap = null;
        editorOriginalBitmap = null;
        editorGeometryBitmap = null;
        editorGeometry = null;
        editorCropDraft = null;
        editorCropView = null;
        editorTargetArtwork = null;
    }

    private void refreshEditorPreview() {
        if (editorPreview == null || editorSourceBitmap == null) return;
        Bitmap preview = editorImageGeometry();
        editorPreview.setImageBitmap(preview);
        updateEditorColorFilter();
    }

    private void updateEditorColorFilter() {
        if (editorPreview == null) return;
        editorPreview.setColorFilter(new ColorMatrixColorFilter(editorColorMatrix()));
    }

    private ColorMatrix editorColorMatrix() {
        float translate = 128f * (1f - editorContrast) + editorBrightness;
        ColorMatrix result = new ColorMatrix(new float[]{
                editorContrast, 0, 0, 0, translate,
                0, editorContrast, 0, 0, translate,
                0, 0, editorContrast, 0, translate,
                0, 0, 0, 1, 0
        });
        if (editorFilter == 1) {
            ColorMatrix grayscale = new ColorMatrix();
            grayscale.setSaturation(0f);
            result.postConcat(grayscale);
        } else if (editorFilter == 2) {
            ColorMatrix warm = new ColorMatrix(new float[]{
                    1.08f, 0, 0, 0, 7,
                    0, 1.01f, 0, 0, 2,
                    0, 0, 0.91f, 0, -4,
                    0, 0, 0, 1, 0
            });
            result.postConcat(warm);
        }
        return result;
    }

    private Bitmap editorImageGeometry() {
        if (editorGeometryBitmap == null) editorGeometryBitmap = CropRenderer.apply(editorSourceBitmap, editorGeometry);
        return editorGeometryBitmap;
    }

    private void saveEditorResult() {
        if (editorSourceBitmap == null) return;
        String cleanTitle = editorTitleDraft.trim();
        String cleanYear = editorYearDraft.trim();
        if (cleanTitle.isEmpty()) cleanTitle = "Без названия";
        try {
            int year = Integer.parseInt(cleanYear);
            if (year < 1000 || year > 2100) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            Toast.makeText(this, "Проверьте год создания", Toast.LENGTH_SHORT).show();
            showEditorScreen(false);
            return;
        }
        if (editorMaterialDraft.isEmpty()) {
            Toast.makeText(this, "Выберите материал", Toast.LENGTH_SHORT).show();
            showEditorScreen(false);
            return;
        }
        try {
            Bitmap geometry = editorImageGeometry();
            Bitmap output = Bitmap.createBitmap(geometry.getWidth(), geometry.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setColorFilter(new ColorMatrixColorFilter(editorColorMatrix()));
            canvas.drawBitmap(geometry, 0, 0, paint);

            File directory = new File(getFilesDir(), "artworks");
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("directory");
            File result = new File(directory, "drawing_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream stream = new FileOutputStream(result);
            output.compress(Bitmap.CompressFormat.JPEG, 94, stream);
            stream.close();
            pendingImageUri = Uri.fromFile(result);
            if (editorTargetArtwork != null) {
                boolean yearChanged = !editorTargetArtwork.year.equals(cleanYear);
                editorTargetArtwork.title = cleanTitle;
                editorTargetArtwork.year = cleanYear;
                editorTargetArtwork.material = joinMaterials(editorMaterialDraft);
                editorTargetArtwork.uri = pendingImageUri.toString();
                if (yearChanged) ArtworkOrder.putFirstInYear(artworks, editorTargetArtwork,
                        artwork -> artwork.year);
                saveData();
                Artwork updated = editorTargetArtwork;
                clearEditorState();
                Toast.makeText(this, "Изменения сохранены", Toast.LENGTH_SHORT).show();
                showArtworkDetail(updated);
            } else {
                pendingTitleDraft = cleanTitle.equals("Без названия") ? "" : cleanTitle;
                pendingYearDraft = cleanYear;
                selectedArtworkMaterials.clear();
                selectedArtworkMaterials.addAll(editorMaterialDraft);
                clearEditorState();
                Toast.makeText(this, "Рисунок готов", Toast.LENGTH_SHORT).show();
                showAddArtwork(false);
            }
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось сохранить изменения", Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap decodeBitmap(Uri uri, int maxSide) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream first = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(first, null, bounds);
            if (first != null) first.close();
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            InputStream second = getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(second, null, options);
            if (second != null) second.close();
            return bitmap;
        } catch (Exception error) {
            return null;
        }
    }

    // Styles for the five approved screens only. Shared collection/crop controls remain unchanged.
    private final int DESIGN_PRIMARY = Color.parseColor("#AD553C");
    private final int DESIGN_TEXT = Color.parseColor("#29241F");
    private final int DESIGN_MUTED = Color.parseColor("#756C62");
    private final int DESIGN_OUTLINE = Color.parseColor("#E7DFD6");
    private final int DESIGN_SOFT = Color.parseColor("#F0EBE4");
    private final int DESIGN_DANGER = Color.parseColor("#B53E32");

    private LinearLayout designHeader(String title, int icon, String description, View.OnClickListener action) {
        LinearLayout header = new LinearLayout(this);
        header.setBaselineAligned(false);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (icon != 0) {
            ImageView back = designIcon(icon, description, DESIGN_TEXT);
            back.setOnClickListener(action);
            header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(58)));
        }
        TextView heading = bodyText(title, 25, DESIGN_TEXT);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, dp(58), 1);
        headingParams.leftMargin = dp(4);
        header.addView(heading, headingParams);
        return header;
    }

    private ImageView designIcon(int resource, String description, int color) {
        ImageView image = new ImageView(this);
        image.setImageResource(resource);
        image.setColorFilter(color);
        image.setScaleType(ImageView.ScaleType.CENTER);
        if (!description.isEmpty()) {
            image.setContentDescription(description);
            image.setFocusable(true);
        } else image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    private Button designBaseButton(String text) {
        Button button = textButton(text, false);
        button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setPadding(dp(14), 0, dp(14), 0);
        return button;
    }

    private Button designPrimaryButton(String text) {
        Button button = designBaseButton(text);
        button.setTextColor(Color.WHITE);
        button.setBackground(rounded(DESIGN_PRIMARY, 14));
        return button;
    }

    private Button designSecondaryButton(String text) {
        Button button = designBaseButton(text);
        button.setTextColor(DESIGN_TEXT);
        button.setBackground(outlined(COLOR_SURFACE, DESIGN_OUTLINE, 14));
        return button;
    }

    private Button designTextButton(String text) {
        Button button = designBaseButton(text);
        button.setTextSize(13);
        button.setTextColor(DESIGN_PRIMARY);
        button.setBackground(rounded(Color.TRANSPARENT, 10));
        button.setPadding(dp(4), 0, dp(8), 0);
        return button;
    }

    private void designButtonIcon(Button button, int resource, int color) {
        android.graphics.drawable.Drawable icon = getDrawable(resource).mutate();
        icon.setTint(color);
        icon.setBounds(0, 0, dp(18), dp(18));
        button.setCompoundDrawablePadding(dp(6));
        button.setCompoundDrawablesRelative(icon, null, null, null);
    }

    private Button designTab(String text, boolean selected) {
        Button button = designBaseButton(text);
        button.setTextSize(14);
        button.setTextColor(selected ? DESIGN_TEXT : DESIGN_MUTED);
        button.setBackground(rounded(selected ? COLOR_SURFACE : Color.TRANSPARENT, 9));
        button.setSelected(selected);
        button.setContentDescription(text + (selected ? ", выбранная вкладка" : ", вкладка"));
        return button;
    }

    private EditText designField(String hint) {
        EditText input = field(hint);
        input.setTextColor(DESIGN_TEXT);
        input.setHintTextColor(DESIGN_MUTED);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackground(outlined(COLOR_SURFACE, DESIGN_OUTLINE, 12));
        return input;
    }

    private View designFormField(String label, View input) {
        LinearLayout block = vertical();
        TextView caption = bodyText(label, 13, DESIGN_TEXT);
        caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setLabelFor(input.getId() == View.NO_ID ? assignDesignId(input) : input.getId());
        block.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));
        block.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return block;
    }

    private int assignDesignId(View view) { int id = View.generateViewId(); view.setId(id); return id; }

    private LinearLayout.LayoutParams designFormParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(74));
        params.topMargin = dp(18);
        return params;
    }

    private LinearLayout designCard() {
        LinearLayout card = vertical();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(outlined(COLOR_SURFACE, DESIGN_OUTLINE, 16));
        return card;
    }

    private View designSourceOption(int resource, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setBaselineAligned(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(10), dp(12));
        row.setBackground(outlined(COLOR_SURFACE, DESIGN_OUTLINE, 16));
        ImageView icon = designIcon(resource, "", DESIGN_PRIMARY);
        row.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout text = vertical();
        text.setPadding(dp(12), 0, dp(6), 0);
        TextView name = bodyText(title, 15, DESIGN_TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.addView(name, lpMatchWrap());
        TextView detail = bodyText(subtitle, 12, DESIGN_MUTED);
        detail.setMaxLines(2);
        LinearLayout.LayoutParams detailParams = lpMatchWrap();
        detailParams.topMargin = dp(4);
        text.addView(detail, detailParams);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(designIcon(R.drawable.ic_design_next, "", DESIGN_MUTED), new LinearLayout.LayoutParams(dp(20), dp(24)));
        row.setContentDescription(title + ". " + subtitle);
        row.setFocusable(true);
        return row;
    }

    private View designPreviewSummary(Uri uri, String title, String subtitle, String action, View.OnClickListener listener) {
        LinearLayout row = designCard();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        pendingPreview = new ImageView(this);
        pendingPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pendingPreview.setBackground(rounded(DESIGN_SOFT, 9));
        pendingPreview.setImageURI(uri);
        row.addView(pendingPreview, new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout text = vertical();
        text.setPadding(dp(12), 0, 0, 0);
        text.addView(bodyText(title, 15, DESIGN_TEXT), lpMatchWrap());
        Button change = designTextButton(action);
        change.setOnClickListener(listener);
        text.addView(change, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(listener);
        pendingPreviewHint = null;
        return row;
    }

    private Button designMaterialButton(ArrayList<String> selected) {
        Button button = designSecondaryButton("");
        button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        updateDesignMaterialButton(button, selected);
        android.graphics.drawable.Drawable arrow = getDrawable(R.drawable.ic_design_down).mutate();
        arrow.setTint(DESIGN_MUTED);
        arrow.setBounds(0, 0, dp(20), dp(20));
        button.setCompoundDrawablesRelative(null, null, arrow, null);
        return button;
    }

    private void updateDesignMaterialButton(Button button, ArrayList<String> selected) {
        button.setText(selected.isEmpty() ? "Выберите материал" : selected.get(0));
        button.setTextColor(selected.isEmpty() ? DESIGN_MUTED : DESIGN_PRIMARY);
    }

    private void keepSingleMaterial(ArrayList<String> selected) {
        while (selected.size() > 1) selected.remove(selected.size() - 1);
    }

    private void showSingleMaterialPicker(ArrayList<String> selected, Runnable update) {
        String[] choices = materials.toArray(new String[0]);
        final int[] choice = {selected.isEmpty() ? -1 : materials.indexOf(selected.get(0))};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Материал")
                .setSingleChoiceItems(choices, choice[0], (picker, which) -> choice[0] = which)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Готово", (picker, which) -> {
                    if (choice[0] >= 0 && choice[0] < choices.length) {
                        selected.clear();
                        selected.add(choices[choice[0]]);
                        update.run();
                    }
                })
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(DESIGN_PRIMARY);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(DESIGN_MUTED);
            if (dialog.getListView() != null) dialog.getListView().setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(rounded(COLOR_SURFACE, 22));
        });
        dialog.show();
    }

    private SeekBar designSeekBar() {
        SeekBar bar = new SeekBar(this);
        bar.setMax(200);
        bar.setPadding(dp(12), 0, dp(12), 0);
        bar.setProgressTintList(ColorStateList.valueOf(DESIGN_PRIMARY));
        bar.setThumbTintList(ColorStateList.valueOf(DESIGN_PRIMARY));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(DESIGN_OUTLINE));
        return bar;
    }

    private TextView designAdjustmentHeader(LinearLayout content, String label, int value) {
        LinearLayout row = new LinearLayout(this);
        row.setBaselineAligned(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = bodyText(label, 13, DESIGN_TEXT);
        row.addView(caption, new LinearLayout.LayoutParams(0, dp(24), 1));
        TextView number = bodyText(designSignedNumber(value), 13, DESIGN_MUTED);
        number.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(number, new LinearLayout.LayoutParams(dp(54), dp(24)));
        LinearLayout.LayoutParams params = lpMatchWrap();
        params.topMargin = dp(12);
        content.addView(row, params);
        return number;
    }

    private String designSignedNumber(int value) { return value > 0 ? "+" + value : String.valueOf(value); }

    private Button editorButton(String label) {
        Button button = textButton(label, false);
        button.setTextSize(13);
        button.setTextColor(COLOR_TEXT);
        button.setBackground(outlined(COLOR_SURFACE, COLOR_OUTLINE, 14));
        return button;
    }

    private TextView sectionLabel(String value) {
        TextView label = bodyText(value, 13, COLOR_PRIMARY);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private LinearLayout.LayoutParams sliderLabelParams() {
        LinearLayout.LayoutParams params = lpMatchWrap();
        params.topMargin = dp(14);
        params.leftMargin = dp(4);
        return params;
    }

    private void showArtworkDetail(Artwork artwork) {
        if (artworkDialog != null) {
            updateArtworkDetail(artwork);
            return;
        }
        // Returning from the editor restores the same grid before reopening its card.
        if ("editor".equals(currentScreen)) showHome();
        final String backgroundScreen = currentScreen;
        if ("home".equals(backgroundScreen)) {
            restoreHomeScroll = true;
            retainedHomeRoot = pageRoot;
            retainedHomeGrid = homeGrid;
        }
        detailArtworks.clear();
        detailArtworks.addAll("home".equals(backgroundScreen) ? visibleArtworks : artworks);
        if (!detailArtworks.contains(artwork)) detailArtworks.add(0, artwork);
        currentScreen = "detail";

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        artworkDialog = dialog;

        artworkCard = vertical();
        artworkCard.setBackground(rounded(COLOR_SURFACE, 20));
        artworkCard.setClipToOutline(true);
        artworkCard.setElevation(dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(8), dp(12));
        LinearLayout heading = vertical();
        artworkDetailTitle = serifHeading(artwork.title, 22);
        artworkDetailTitle.setMaxLines(2);
        artworkDetailTitle.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(artworkDetailTitle, lpMatchWrap());
        artworkDetailMetadata = bodyText("", 12, COLOR_MUTED);
        artworkDetailMetadata.setMaxLines(2);
        artworkDetailMetadata.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metadataParams = lpMatchWrap();
        metadataParams.topMargin = dp(4);
        heading.addView(artworkDetailMetadata, metadataParams);
        header.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView more = iconText("⋮", 24);
        more.setContentDescription("Действия с рисунком");
        more.setOnClickListener(this::showArtworkActions);
        header.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_action_close);
        close.setColorFilter(COLOR_TEXT);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setContentDescription("Закрыть карточку рисунка");
        close.setBackground(rounded(COLOR_SURFACE, 12));
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        artworkCard.addView(header, lpMatchWrap());

        artworkDetailImageContainer = new FrameLayout(this);
        artworkDetailImageContainer.setBackgroundColor(COLOR_SURFACE_2);
        artworkCard.addView(artworkDetailImageContainer,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));
        artworkDetailBackground = new ImageView(this);
        artworkDetailBackground.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artworkDetailBackground.setAlpha(0.82f);
        artworkDetailBackground.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            artworkDetailBackground.setRenderEffect(RenderEffect.createBlurEffect(
                    dp(18), dp(18), Shader.TileMode.CLAMP));
        }
        artworkDetailImageContainer.addView(artworkDetailBackground, match());
        artworkDetailImage = new ImageView(this);
        artworkDetailImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        artworkDetailImage.setFocusable(true);
        artworkDetailImage.setClickable(true);
        artworkDetailImageContainer.addView(artworkDetailImage, match());
        bindArtworkGestures(artworkDetailImage, this::showFullscreenArtwork);

        artworkDetailCounter = bodyText("", 11, Color.WHITE);
        artworkDetailCounter.setGravity(Gravity.CENTER);
        artworkDetailCounter.setPadding(dp(8), dp(4), dp(8), dp(4));
        artworkDetailCounter.setBackground(rounded(Color.parseColor("#99000000"), 12));
        artworkDetailCounter.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams counterParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        counterParams.leftMargin = dp(12);
        counterParams.bottomMargin = dp(12);
        artworkDetailImageContainer.addView(artworkDetailCounter, counterParams);
        ImageView expand = roundAction(R.drawable.ic_action_expand, "Открыть изображение на весь экран");
        expand.setColorFilter(Color.WHITE);
        expand.setBackground(rounded(Color.parseColor("#99000000"), 22));
        expand.setOnClickListener(view -> showFullscreenArtwork());
        FrameLayout.LayoutParams expandParams = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM | Gravity.END);
        expandParams.rightMargin = dp(8);
        expandParams.bottomMargin = dp(8);
        artworkDetailImageContainer.addView(expand, expandParams);
        dialog.setContentView(artworkCard);
        dialog.setOnDismissListener(value -> {
            if (artworkDialog != dialog) return;
            if (artworkActionsPopup != null) artworkActionsPopup.dismiss();
            if (fullscreenArtworkDialog != null) fullscreenArtworkDialog.dismiss();
            if (artworkDetailImage != null) artworkDetailImage.animate().cancel();
            artworkDialog = null;
            artworkCard = null;
            artworkDetailImageContainer = null;
            artworkDetailBackground = null;
            artworkDetailImage = null;
            artworkDetailTitle = null;
            artworkDetailMetadata = null;
            artworkDetailCounter = null;
            displayedArtwork = null;
            detailArtworks.clear();
            if ("detail".equals(currentScreen)) {
                currentScreen = backgroundScreen;
                restoreHomeScroll = false;
                retainedHomeRoot = null;
                retainedHomeGrid = null;
            }
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.34f);
            window.setGravity(Gravity.CENTER);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        updateArtworkDetail(artwork);
        dialog.show();
        resizeArtworkCard();
    }

    private void bindArtworkGestures(ImageView image, Runnable onTap) {
        image.setFocusable(true);
        image.setClickable(true);
        image.setOnClickListener(view -> onTap.run());
        final float[] start = new float[2];
        final boolean[] active = {false};
        image.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = event.getX();
                    start[1] = event.getY();
                    active[0] = true;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    active[0] = false;
                    return true;
                case MotionEvent.ACTION_UP:
                    if (active[0]) {
                        float horizontal = event.getX() - start[0];
                        float vertical = event.getY() - start[1];
                        if (Math.abs(horizontal) >= dp(48)
                                && Math.abs(horizontal) > Math.abs(vertical) * 1.25f) {
                            switchArtworkDetail(horizontal < 0 ? 1 : -1);
                        } else if (Math.hypot(horizontal, vertical) <= dp(12)) {
                            view.performClick();
                        }
                    }
                    active[0] = false;
                    return true;
                default:
                    return true;
            }
        });
        image.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (detailArtworks.size() > 1) {
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "Следующий рисунок"));
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "Предыдущий рисунок"));
                }
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                if (detailArtworks.size() > 1) {
                    if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                        switchArtworkDetail(1);
                        return true;
                    }
                    if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                        switchArtworkDetail(-1);
                        return true;
                    }
                }
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
        image.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    && keyCode != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
                switchArtworkDetail(keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            }
            return true;
        });
    }

    private void showFullscreenArtwork() {
        if (displayedArtwork == null || (fullscreenArtworkDialog != null && fullscreenArtworkDialog.isShowing())) return;
        Dialog viewer = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        viewer.requestWindowFeature(Window.FEATURE_NO_TITLE);
        viewer.setCancelable(true);
        fullscreenArtworkDialog = viewer;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = 0;
            int bottom = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets.getDisplayCutout() != null) {
                top = insets.getDisplayCutout().getSafeInsetTop();
                bottom = insets.getDisplayCutout().getSafeInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });
        fullscreenArtworkImage = new ImageView(this);
        fullscreenArtworkImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(fullscreenArtworkImage, match());

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(8), dp(12), dp(8));
        toolbar.setBackgroundColor(Color.parseColor("#88000000"));
        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_action_close);
        close.setColorFilter(Color.WHITE);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setContentDescription("Закрыть полноэкранный просмотр");
        close.setOnClickListener(view -> viewer.dismiss());
        toolbar.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        fullscreenArtworkCounter = bodyText("", 13, Color.WHITE);
        fullscreenArtworkCounter.setGravity(Gravity.CENTER);
        fullscreenArtworkCounter.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        toolbar.addView(fullscreenArtworkCounter, new LinearLayout.LayoutParams(0, dp(44), 1));
        toolbar.addView(new View(this), new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(toolbar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60), Gravity.TOP));

        fullscreenArtworkTitle = bodyText("", 16, Color.WHITE);
        fullscreenArtworkTitle.setGravity(Gravity.CENTER);
        fullscreenArtworkTitle.setMaxLines(2);
        fullscreenArtworkTitle.setEllipsize(TextUtils.TruncateAt.END);
        fullscreenArtworkTitle.setPadding(dp(20), dp(12), dp(20), dp(24));
        fullscreenArtworkTitle.setBackgroundColor(Color.parseColor("#88000000"));
        root.addView(fullscreenArtworkTitle, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        bindArtworkGestures(fullscreenArtworkImage, () -> {
            int visibility = toolbar.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE;
            toolbar.setVisibility(visibility);
            fullscreenArtworkTitle.setVisibility(visibility);
        });
        viewer.setContentView(root);
        viewer.setOnDismissListener(dialog -> {
            if (fullscreenArtworkDialog != viewer) return;
            fullscreenArtworkDialog = null;
            fullscreenArtworkImage = null;
            fullscreenArtworkCounter = null;
            fullscreenArtworkTitle = null;
        });
        Window window = viewer.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setNavigationBarColor(Color.BLACK);
            window.setStatusBarColor(Color.BLACK);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
                window.setAttributes(attributes);
            }
        }
        updateFullscreenArtwork();
        viewer.show();
        if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.requestApplyInsets();
    }

    private void updateFullscreenArtwork() {
        if (fullscreenArtworkImage == null || displayedArtwork == null) return;
        setArtworkImage(fullscreenArtworkImage, displayedArtwork);
        fullscreenArtworkImage.setContentDescription(displayedArtwork.title + ". Полноэкранный просмотр. Рисунок "
                + (detailArtworks.indexOf(displayedArtwork) + 1) + " из " + detailArtworks.size());
        fullscreenArtworkCounter.setText(getString(R.string.artwork_position,
                detailArtworks.indexOf(displayedArtwork) + 1, detailArtworks.size()));
        fullscreenArtworkTitle.setText(displayedArtwork.title);
    }

    private void showArtworkActions(View anchor) {
        final Artwork target = displayedArtwork;
        if (target == null || artworkCard == null) return;
        if (artworkActionsPopup != null) {
            artworkActionsPopup.dismiss();
            return;
        }
        int menuBorder = Color.parseColor("#E6DDD5");
        int deleteColor = Color.parseColor("#B3372E");
        LinearLayout content = vertical();
        content.setPadding(dp(6), dp(6), dp(6), dp(6));
        content.setBackground(outlined(COLOR_SURFACE, menuBorder, 16));
        content.setClipToOutline(true);
        TextView measuringLabel = bodyText("Редактировать", 13, COLOR_TEXT);
        int width = Math.max(dp(202), (int) Math.ceil(Math.max(
                measuringLabel.getPaint().measureText("Редактировать"),
                measuringLabel.getPaint().measureText("Удалить работу"))) + dp(66));
        width = Math.min(width, artworkCard.getWidth() - dp(36));
        PopupWindow menu = new PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        menu.setBackgroundDrawable(outlined(COLOR_SURFACE, menuBorder, 16));
        menu.setElevation(dp(12));
        menu.setOutsideTouchable(true);
        menu.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        content.addView(artworkActionItem("Редактировать", R.drawable.ic_menu_edit, COLOR_TEXT, () -> {
            menu.dismiss();
            editorTargetArtwork = target;
            showImageEditor(Uri.parse(target.uri));
            if (!"editor".equals(currentScreen)) editorTargetArtwork = null;
        }), lpMatchWrap());
        content.addView(artworkActionItem("Заменить фото", R.drawable.ic_source_gallery, COLOR_TEXT, () -> {
            menu.dismiss();
            showImageSourceDialog(target);
        }), lpMatchWrap());
        content.addView(artworkActionItem("Поделиться", R.drawable.ic_menu_share, COLOR_TEXT, () -> {
            menu.dismiss();
            shareArtwork(target);
        }), lpMatchWrap());
        View divider = new View(this);
        divider.setBackgroundColor(menuBorder);
        content.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        content.addView(artworkActionItem("Удалить работу", R.drawable.ic_menu_delete, deleteColor, () -> {
            menu.dismiss();
            confirmDelete(target);
        }), lpMatchWrap());
        artworkActionsPopup = menu;
        anchor.setBackground(rounded(COLOR_SURFACE_2, 12));
        menu.setOnDismissListener(() -> {
            if (artworkActionsPopup == menu) artworkActionsPopup = null;
            anchor.setBackgroundColor(Color.TRANSPARENT);
        });
        int[] cardPosition = new int[2];
        int[] anchorPosition = new int[2];
        artworkCard.getLocationOnScreen(cardPosition);
        anchor.getLocationOnScreen(anchorPosition);
        int offsetX = cardPosition[0] + artworkCard.getWidth() - dp(18)
                - anchorPosition[0] - anchor.getWidth();
        menu.showAsDropDown(anchor, offsetX, -dp(4), Gravity.END);
    }

    private View artworkActionItem(String label, int iconResource, int color, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setMinimumHeight(dp(48));
        row.setFocusable(true);
        row.setContentDescription(label);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(COLOR_SURFACE_2),
                null, rounded(Color.WHITE, 10)));
        row.setOnClickListener(view -> action.run());
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setColorFilter(color);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView title = bodyText(label, 13, color);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = dp(10);
        row.addView(title, titleParams);
        return row;
    }

    private void updateArtworkDetail(Artwork artwork) {
        displayedArtwork = artwork;
        artworkDetailTitle.setText(artwork.title);
        artworkDetailTitle.setContentDescription(artwork.title);
        String metadata = artwork.year;
        if (!artwork.material.isEmpty()) {
            metadata += (metadata.isEmpty() ? "" : " · ") + artwork.material;
        }
        artworkDetailMetadata.setText(metadata);
        artworkDetailMetadata.setContentDescription(metadata);
        setArtworkImage(artworkDetailImage, artwork);
        updateArtworkBackdrop(artwork);
        int position = detailArtworks.indexOf(artwork) + 1;
        artworkDetailImage.setContentDescription(artwork.title + ". Рисунок " + position
                + " из " + detailArtworks.size());
        artworkDetailCounter.setText(getString(R.string.artwork_position, position, detailArtworks.size()));
        resizeArtworkCard();
        updateFullscreenArtwork();
    }

    private void updateArtworkBackdrop(Artwork artwork) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.graphics.drawable.Drawable drawable = artworkDetailImage.getDrawable();
            android.graphics.drawable.Drawable.ConstantState state = drawable == null ? null : drawable.getConstantState();
            artworkDetailBackground.setImageDrawable(state == null ? drawable
                    : state.newDrawable(getResources()).mutate());
            return;
        }
        // Older Android versions use a small blurred bitmap rather than a render effect.
        Bitmap source = decodeBitmap(Uri.parse(artwork.uri), 200);
        if (source == null) {
            artworkDetailBackground.setImageDrawable(null);
            return;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        int[] horizontal = new int[pixels.length];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        blurBackdropPass(pixels, horizontal, width, height, 6, true);
        blurBackdropPass(horizontal, pixels, width, height, 6, false);
        Bitmap blurred = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        source.recycle();
        artworkDetailBackground.setImageBitmap(blurred);
    }

    private static void blurBackdropPass(int[] source, int[] target, int width, int height,
                                         int radius, boolean horizontal) {
        int lines = horizontal ? height : width;
        int length = horizontal ? width : height;
        int step = horizontal ? 1 : width;
        int divisor = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * width : line;
            int alpha = 0, red = 0, green = 0, blue = 0;
            for (int k = -radius; k <= radius; k++) {
                int color = source[base + Math.max(0, Math.min(length - 1, k)) * step];
                alpha += color >>> 24;
                red += (color >>> 16) & 255;
                green += (color >>> 8) & 255;
                blue += color & 255;
            }
            for (int i = 0; i < length; i++) {
                target[base + i * step] = (alpha / divisor << 24) | (red / divisor << 16)
                        | (green / divisor << 8) | blue / divisor;
                int outgoing = source[base + Math.max(0, i - radius) * step];
                int incoming = source[base + Math.min(length - 1, i + radius + 1) * step];
                alpha += (incoming >>> 24) - (outgoing >>> 24);
                red += ((incoming >>> 16) & 255) - ((outgoing >>> 16) & 255);
                green += ((incoming >>> 8) & 255) - ((outgoing >>> 8) & 255);
                blue += (incoming & 255) - (outgoing & 255);
            }
        }
    }

    private void resizeArtworkCard() {
        if (artworkDialog == null || artworkDialog.getWindow() == null || artworkCard == null) return;
        int availableWidth = pageRoot.getWidth();
        int availableHeight = pageRoot.getHeight() - pageRoot.getPaddingTop() - pageRoot.getPaddingBottom();
        if (availableWidth <= 0) availableWidth = getResources().getDisplayMetrics().widthPixels;
        if (availableHeight <= 0) availableHeight = getResources().getDisplayMetrics().heightPixels - dp(48);
        int width = Math.max(1, Math.min(dp(420), availableWidth - dp(24)));
        ViewGroup.LayoutParams imageParams = artworkDetailImageContainer.getLayoutParams();
        int preferredImageHeight = Math.min(dp(420), Math.round(availableHeight * 0.68f));
        imageParams.height = preferredImageHeight;
        artworkDetailImageContainer.setLayoutParams(imageParams);
        artworkCard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int fixedHeight = artworkCard.getMeasuredHeight() - imageParams.height;
        imageParams.height = Math.max(1, Math.min(preferredImageHeight, availableHeight - dp(48) - fixedHeight));
        artworkDetailImageContainer.setLayoutParams(imageParams);
        artworkDialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void switchArtworkDetail(int direction) {
        if (artworkDialog == null || detailArtworks.size() < 2) return;
        int index = detailArtworks.indexOf(displayedArtwork);
        int next = (index + direction + detailArtworks.size()) % detailArtworks.size();
        artworkDetailImage.animate().cancel();
        updateArtworkDetail(detailArtworks.get(next));
        artworkDetailImage.setTranslationX(dp(16) * direction);
        artworkDetailImage.setAlpha(0.8f);
        artworkDetailImage.animate().translationX(0).alpha(1f).setDuration(160).start();
    }

    private void confirmDelete(Artwork artwork) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить работу?")
                .setMessage("Запись будет удалена из приложения. Сам файл рисунка останется на телефоне.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    artworks.remove(artwork);
                    saveData();
                    showHome();
                })
                .show();
    }

    private void shareArtwork(Artwork artwork) {
        Bitmap bitmap = decodeBitmap(Uri.parse(artwork.uri), 2000);
        if (bitmap == null) {
            Toast.makeText(this, "Не удалось подготовить рисунок", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File directory = new File(getCacheDir(), "captures");
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("directory");
            File file = new File(directory, "share_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream output = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output);
            output.close();
            Uri uri = CaptureFileProvider.uriForFile(this, file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("image/jpeg");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Поделиться рисунком"));
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось поделиться рисунком", Toast.LENGTH_SHORT).show();
        }
    }

    private void showYears() {
        currentScreen = "years";
        FrameLayout root = newPage();
        LinearLayout page = vertical();
        page.setPadding(dp(18), dp(10), dp(18), dp(82));
        root.addView(page, match());
        page.addView(topHeading(root, "По годам", "drawings"),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = vertical();
        list.setPadding(0, dp(2), 0, dp(18));
        scroll.addView(list, lpMatchWrap());
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        if (artworks.isEmpty()) {
            TextView empty = bodyText("Здесь появится архив по годам", 16, COLOR_MUTED);
            empty.setGravity(Gravity.CENTER);
            list.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        } else {
            Map<String, List<Artwork>> grouped = new LinkedHashMap<>();
            for (Artwork artwork : artworks) {
                if (!grouped.containsKey(artwork.year)) grouped.put(artwork.year, new ArrayList<>());
                grouped.get(artwork.year).add(artwork);
            }
            for (Map.Entry<String, List<Artwork>> group : grouped.entrySet()) {
                LinearLayout yearHeader = new LinearLayout(this);
                yearHeader.setGravity(Gravity.CENTER_VERTICAL);
                TextView year = serifHeading(group.getKey(), 27);
                TextView count = bodyText(group.getValue().size() + " работ", 13, COLOR_MUTED);
                count.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
                TextView arrow = iconText("›", 29);
                yearHeader.addView(year, new LinearLayout.LayoutParams(0, dp(52), 1));
                yearHeader.addView(count, new LinearLayout.LayoutParams(dp(82), dp(52)));
                yearHeader.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(52)));
                list.addView(yearHeader, lpMatchWrap());
                LinearLayout strip = new LinearLayout(this);
                strip.setOrientation(LinearLayout.HORIZONTAL);
                int countShown = Math.min(4, group.getValue().size());
                for (int i = 0; i < countShown; i++) strip.addView(yearThumbnail(group.getValue().get(i)));
                for (int i = countShown; i < 4; i++) {
                    View placeholder = new View(this);
                    placeholder.setBackground(rounded(COLOR_SURFACE_2, 7));
                    LinearLayout.LayoutParams placeholderParams = new LinearLayout.LayoutParams(0, dp(108), 1);
                    if (i > 0) placeholderParams.leftMargin = dp(6);
                    strip.addView(placeholder, placeholderParams);
                }
                LinearLayout.LayoutParams stripParams = lpMatchWrap();
                stripParams.bottomMargin = dp(22);
                list.addView(strip, stripParams);
            }
        }
        addBottomNavigation(root, "По годам");
    }

    private View yearThumbnail(Artwork artwork) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(rounded(COLOR_SURFACE_2, 13));
        image.setClipToOutline(true);
        thumbnailLoader.bind(image, null, artwork.uri, dp(108));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(108), 1);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        image.setLayoutParams(params);
        image.setOnClickListener(v -> showArtworkDetail(artwork));
        return image;
    }

    private View yearRow(Artwork artwork) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(14), dp(10));
        row.setBackground(rounded(COLOR_SURFACE, 16));
        row.setElevation(dp(2));

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(rounded(COLOR_SURFACE_2, 12));
        image.setClipToOutline(true);
        thumbnailLoader.bind(image, null, artwork.uri, dp(74));
        row.addView(image, new LinearLayout.LayoutParams(dp(74), dp(74)));

        LinearLayout text = vertical();
        text.setPadding(dp(14), 0, 0, 0);
        TextView name = bodyText(artwork.title, 16, COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.addView(name, lpMatchWrap());
        text.addView(bodyText(artwork.material, 14, COLOR_MUTED), lpMatchWrap());
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> showArtworkDetail(artwork));
        return row;
    }

    private void showSettings() {
        currentScreen = "settings";
        FrameLayout root = newPage();
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = vertical();
        page.setPadding(dp(18), dp(6), dp(18), dp(86));
        scroll.addView(page, lpMatchWrap());
        root.addView(scroll, match());
        page.addView(designHeader("Настройки", 0, "", null), lpMatchWrap());
        LinearLayout materialsLink = designCard();
        materialsLink.setOrientation(LinearLayout.HORIZONTAL);
        materialsLink.setBaselineAligned(false);
        materialsLink.setGravity(Gravity.CENTER_VERTICAL);
        materialsLink.addView(designIcon(R.drawable.ic_nav_materials, "", DESIGN_TEXT),
                new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout materialsText = vertical();
        TextView materialsTitle = bodyText("Материалы", 15, DESIGN_TEXT);
        materialsTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        materialsText.addView(materialsTitle, lpMatchWrap());
        TextView materialsHint = bodyText("Добавить, переименовать, удалить", 12, DESIGN_MUTED);
        LinearLayout.LayoutParams hintParams = lpMatchWrap();
        hintParams.topMargin = dp(4);
        materialsText.addView(materialsHint, hintParams);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.leftMargin = dp(12);
        textParams.rightMargin = dp(8);
        materialsLink.addView(materialsText, textParams);
        materialsLink.addView(designIcon(R.drawable.ic_design_next, "", DESIGN_MUTED),
                new LinearLayout.LayoutParams(dp(20), dp(24)));
        materialsLink.setFocusable(true);
        materialsLink.setContentDescription("Материалы. Добавить, переименовать, удалить");
        materialsLink.setOnClickListener(v -> showMaterials());
        LinearLayout.LayoutParams materialsParams = lpMatchWrap();
        materialsParams.topMargin = dp(10);
        page.addView(materialsLink, materialsParams);
        boolean backupEnabled = DrawingBackup.enabled(this);
        LinearLayout dailyCard = designCard();
        LinearLayout.LayoutParams dailyParams = lpMatchWrap();
        dailyParams.topMargin = dp(10);
        page.addView(dailyCard, dailyParams);
        LinearLayout dailyHeader = new LinearLayout(this);
        dailyHeader.setGravity(Gravity.CENTER_VERTICAL);
        dailyHeader.setBaselineAligned(false);
        TextView dailyTitle = bodyText("Ежедневная копия", 16, DESIGN_TEXT);
        dailyTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dailyHeader.addView(dailyTitle, new LinearLayout.LayoutParams(0, dp(44), 1));
        Switch dailyToggle = new Switch(this);
        dailyToggle.setContentDescription("Ежедневная резервная копия");
        dailyToggle.setChecked(backupEnabled);
        dailyToggle.setSwitchMinWidth(dp(48));
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.WHITE);
        thumb.setSize(dp(22), dp(22));
        dailyToggle.setThumbDrawable(thumb);
        android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
        GradientDrawable checkedTrack = rounded(DESIGN_PRIMARY, 14);
        checkedTrack.setSize(dp(48), dp(28));
        GradientDrawable uncheckedTrack = rounded(COLOR_OUTLINE, 14);
        uncheckedTrack.setSize(dp(48), dp(28));
        track.addState(new int[]{android.R.attr.state_checked}, checkedTrack);
        track.addState(new int[]{}, uncheckedTrack);
        dailyToggle.setTrackDrawable(track);
        dailyHeader.addView(dailyToggle, new LinearLayout.LayoutParams(dp(52), dp(44)));
        dailyCard.addView(dailyHeader, lpMatchWrap());
        dailyCard.addView(bodyText(backupEnabled ? "Включена · автоматически каждый день" : "Выключена", 12, DESIGN_MUTED), lpMatchWrap());
        if (backupEnabled) {
            LinearLayout folderRow = new LinearLayout(this);
            folderRow.setBaselineAligned(false);
            folderRow.setGravity(Gravity.CENTER_VERTICAL);
            folderRow.setPadding(dp(12), 0, dp(4), 0);
            folderRow.setBackground(rounded(DESIGN_SOFT, 12));
            ImageView folderIcon = designIcon(R.drawable.ic_folder, "", DESIGN_PRIMARY);
            folderRow.addView(folderIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView folderName = bodyText(DrawingBackup.folderName(this), 14, DESIGN_TEXT);
            folderName.setSingleLine(true);
            folderName.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams folderNameParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            folderNameParams.leftMargin = dp(10);
            folderRow.addView(folderName, folderNameParams);
            ImageView editFolder = designIcon(R.drawable.ic_design_edit, "Изменить папку", DESIGN_MUTED);
            editFolder.setOnClickListener(v -> openBackupFolderPicker());
            folderRow.addView(editFolder, new LinearLayout.LayoutParams(dp(44), dp(48)));
            LinearLayout.LayoutParams folderParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            folderParams.topMargin = dp(14);
            dailyCard.addView(folderRow, folderParams);
            TextView status = bodyText(DrawingBackup.status(this), 12, DESIGN_MUTED);
            status.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams statusParams = lpMatchWrap();
            statusParams.topMargin = dp(12);
            dailyCard.addView(status, statusParams);
        } else {
            Button chooseFolder = designSecondaryButton("Выбрать папку");
            LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            chooseParams.topMargin = dp(14);
            dailyCard.addView(chooseFolder, chooseParams);
            chooseFolder.setOnClickListener(v -> openBackupFolderPicker());
        }
        dailyToggle.setOnCheckedChangeListener((button, checked) -> {
            boolean currentlyEnabled = DrawingBackup.enabled(this);
            if (checked && !currentlyEnabled) { button.setChecked(false); openBackupFolderPicker(); }
            else if (!checked && currentlyEnabled) { DrawingBackup.disable(this); showSettings(); }
        });
        LinearLayout backupCard = designCard();
        LinearLayout.LayoutParams backupCardParams = lpMatchWrap();
        backupCardParams.topMargin = dp(14);
        page.addView(backupCard, backupCardParams);
        TextView backupTitle = bodyText("Резервная копия", 16, DESIGN_TEXT);
        backupTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        backupCard.addView(backupTitle, lpMatchWrap());
        Button createZip = designPrimaryButton("Создать копию ZIP");
        designButtonIcon(createZip, R.drawable.ic_design_download, Color.WHITE);
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        createParams.topMargin = dp(16);
        backupCard.addView(createZip, createParams);
        createZip.setOnClickListener(v -> openBackupCreatePicker());
        Button restoreZip = designSecondaryButton("Восстановить из ZIP");
        designButtonIcon(restoreZip, R.drawable.ic_design_upload, DESIGN_TEXT);
        LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        restoreParams.topMargin = dp(10);
        backupCard.addView(restoreZip, restoreParams);
        restoreZip.setOnClickListener(v -> openBackupImportPicker());
        addBottomNavigation(root, "Настройки");
    }

    private void showMaterials() {
        currentScreen = "materials";
        FrameLayout root = newPage();
        LinearLayout page = vertical();
        page.setPadding(dp(18), dp(6), dp(18), dp(86));
        root.addView(page, match());
        LinearLayout header = designHeader("Материалы", R.drawable.ic_design_back, "Назад в настройки",
                v -> showSettings());
        ImageView addIcon = designIcon(R.drawable.ic_design_plus, "Добавить материал", DESIGN_PRIMARY);
        header.addView(addIcon, new LinearLayout.LayoutParams(dp(44), dp(52)));
        page.addView(header, lpMatchWrap());
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(0, dp(10), 0, dp(12));
        scroll.addView(body, lpMatchWrap());
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout materialList = vertical();
        materialList.setBackground(outlined(COLOR_SURFACE, DESIGN_OUTLINE, 16));
        body.addView(materialList, lpMatchWrap());
        renderMaterialList(materialList);
        Button add = designSecondaryButton("Добавить материал");
        designButtonIcon(add, R.drawable.ic_design_plus, DESIGN_TEXT);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        addParams.topMargin = dp(14);
        body.addView(add, addParams);
        View.OnClickListener addAction = v -> showAddMaterialDialog(material -> renderMaterialList(materialList));
        add.setOnClickListener(addAction);
        addIcon.setOnClickListener(addAction);
        addBottomNavigation(root, "Настройки");
    }

    private LinearLayout settingsCard() {
        LinearLayout card = vertical();
        card.setPadding(dp(16), dp(17), dp(16), dp(17));
        card.setBackground(rounded(COLOR_SURFACE, 18));
        card.setElevation(dp(1));
        return card;
    }

    private void openBackupFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_BACKUP_FOLDER);
    }

    private void openBackupCreatePicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        String stamp = new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(new Date());
        intent.putExtra(Intent.EXTRA_TITLE, "drawings-" + stamp + ".zip");
        startActivityForResult(intent, REQUEST_CREATE_BACKUP);
    }

    private void openBackupImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/octet-stream"
        });
        startActivityForResult(intent, REQUEST_OPEN_BACKUP);
    }

    private AlertDialog backupProgress(String message) {
        LinearLayout content = vertical();
        content.setPadding(dp(24), dp(10), dp(24), dp(20));
        android.widget.ProgressBar progress = new android.widget.ProgressBar(this);
        progress.setIndeterminate(true);
        content.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        TextView label = bodyText(message, 14, COLOR_MUTED);
        label.setGravity(Gravity.CENTER);
        content.addView(label, lpMatchWrap());
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        dialog.setCancelable(false);
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(rounded(COLOR_BG, 22));
        });
        dialog.show();
        return dialog;
    }

    private void exportBackup(Uri destination) {
        AlertDialog progress = backupProgress("Создаём резервную копию…");
        new Thread(() -> {
            boolean success;
            try {
                DrawingBackup.exportTo(getApplicationContext(), destination);
                success = true;
            } catch (Exception error) {
                success = false;
            }
            boolean completed = success;
            runOnUiThread(() -> {
                progress.dismiss();
                Toast.makeText(this, completed
                                ? "ZIP-копия сохранена"
                                : "Не удалось создать ZIP. Проверьте свободное место и повторите",
                        Toast.LENGTH_LONG).show();
            });
        }, "drawing-export").start();
    }

    private void inspectBackup(Uri source) {
        AlertDialog progress = backupProgress("Проверяем резервную копию…");
        new Thread(() -> {
            DrawingBackup.Archive archive = null;
            try {
                archive = DrawingBackup.inspect(getApplicationContext(), source);
                DrawingBackup.Archive ready = archive;
                runOnUiThread(() -> {
                    progress.dismiss();
                    showRestoreConfirmation(ready);
                });
            } catch (Exception error) {
                if (archive != null) archive.close();
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this,
                            "Не удалось прочитать копию. Выберите ZIP, созданный приложением. Коллекция не изменена.",
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "drawing-backup-inspect").start();
    }

    private void showRestoreConfirmation(DrawingBackup.Archive archive) {
        int existing = archive.totalCount() - archive.missingCount;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Восстановить коллекцию?")
                .setMessage("Работ в копии: " + archive.totalCount()
                        + "\nНовых: " + archive.missingCount
                        + "\nУже в коллекции: " + existing)
                .setNegativeButton("Отмена", (ignored, which) -> archive.close())
                .setPositiveButton("Восстановить", (ignored, which) -> restoreBackup(archive))
                .setOnCancelListener(ignored -> archive.close())
                .create();
        dialog.show();
    }

    private void restoreBackup(DrawingBackup.Archive archive) {
        AlertDialog progress = backupProgress("Восстанавливаем рисунки…");
        new Thread(() -> {
            int added = -1;
            try {
                added = DrawingBackup.restoreMissing(getApplicationContext(), archive);
            } catch (Exception ignored) {
            } finally {
                archive.close();
            }
            int restored = added;
            runOnUiThread(() -> {
                progress.dismiss();
                if (restored >= 0) {
                    loadData();
                    showSettings();
                    Toast.makeText(this, restored == 0
                                    ? "Все работы из ZIP уже есть в коллекции"
                                    : "Добавлено работ: " + restored,
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this,
                            "Не удалось восстановить ZIP. Существующая коллекция не изменена.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "drawing-backup-restore").start();
    }

    private void renderMaterialList(LinearLayout list) {
        list.removeAllViews();
        for (int index = 0; index < materials.size(); index++) {
            String material = materials.get(index);
            if (index > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(DESIGN_OUTLINE);
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                dividerParams.leftMargin = dp(14);
                dividerParams.rightMargin = dp(14);
                list.addView(divider, dividerParams);
            }
            LinearLayout row = new LinearLayout(this);
            row.setBaselineAligned(false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(5), dp(6), dp(5));
            TextView name = bodyText(material, 14, DESIGN_TEXT);
            name.setMaxLines(2);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ImageView rename = designIcon(R.drawable.ic_design_edit, "Переименовать материал " + material, DESIGN_MUTED);
            rename.setOnClickListener(v -> showRenameMaterialDialog(material, list));
            row.addView(rename, new LinearLayout.LayoutParams(dp(44), dp(44)));
            ImageView remove = designIcon(R.drawable.ic_design_trash, "Удалить материал " + material, DESIGN_DANGER);
            remove.setOnClickListener(v -> confirmDeleteMaterial(material, list));
            row.addView(remove, new LinearLayout.LayoutParams(dp(44), dp(44)));
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
        }
    }

    private void showRenameMaterialDialog(String material, LinearLayout list) {
        EditText input = designField("Название материала");
        input.setText(material);
        input.setSelection(input.length());
        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(dp(20), dp(6), dp(20), 0);
        holder.addView(input, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        new AlertDialog.Builder(this)
                .setTitle("Переименовать материал")
                .setView(holder)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty() || value.equals(material)) return;
                    if (materials.contains(value)) {
                        Toast.makeText(this, "Такой материал уже есть", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int index = materials.indexOf(material);
                    if (index >= 0) materials.set(index, value);
                    for (Artwork artwork : artworks) {
                        ArrayList<String> values = artwork.materialList();
                        int oldIndex = values.indexOf(material);
                        if (oldIndex >= 0) {
                            values.set(oldIndex, value);
                            artwork.material = joinMaterials(values);
                        }
                    }
                    int filterIndex = selectedMaterialFilters.indexOf(material);
                    if (filterIndex >= 0) selectedMaterialFilters.set(filterIndex, value);
                    saveData();
                    renderMaterialList(list);
                })
                .show();
    }

    private void confirmDeleteMaterial(String material, LinearLayout list) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить материал?")
                .setMessage("«" + material + "» исчезнет из списка. У уже сохранённых работ он останется.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    materials.remove(material);
                    selectedMaterialFilters.remove(material);
                    selectedArtworkMaterials.remove(material);
                    saveData();
                    renderMaterialList(list);
                })
                .show();
    }

    private View topHeading(FrameLayout root, String title, String activeSection) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView heading = serifHeading(title, 28);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        headingParams.leftMargin = dp(4);
        header.addView(heading, headingParams);
        return header;
    }

    private void addBottomNavigation(FrameLayout root, String active) {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(20), dp(5), dp(20), dp(5));
        nav.setBackgroundColor(COLOR_BG);

        boolean collectionActive = active.equals("Коллекция") || active.equals("По годам");
        View collection = navItem(R.drawable.ic_nav_works, "Коллекция", collectionActive);
        collection.setOnClickListener(v -> {
            if (active.equals("Коллекция")) showYears();
            else showHome();
        });
        nav.addView(collection, navItemParams());

        ImageView add = new ImageView(this);
        add.setImageResource(R.drawable.ic_action_add);
        add.setColorFilter(Color.WHITE);
        add.setScaleType(ImageView.ScaleType.CENTER);
        add.setContentDescription("Добавить рисунок");
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(COLOR_PRIMARY);
        add.setBackground(circle);
        add.setElevation(dp(4));
        add.setOnClickListener(v -> showAddArtwork());
        LinearLayout center = vertical();
        center.setGravity(Gravity.CENTER);
        center.addView(add, new LinearLayout.LayoutParams(dp(54), dp(54)));
        nav.addView(center, navItemParams());

        View settings = navItem(R.drawable.ic_nav_settings, "Настройки", active.equals("Настройки"));
        settings.setOnClickListener(v -> showSettings());
        nav.addView(settings, navItemParams());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70), Gravity.BOTTOM);
        root.addView(nav, params);
    }

    private View navItem(int iconResource, String label, boolean active) {
        LinearLayout item = vertical();
        item.setGravity(Gravity.CENTER);
        item.setBackgroundColor(Color.TRANSPARENT);
        item.setClickable(true);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setColorFilter(active ? COLOR_PRIMARY : COLOR_TEXT);
        item.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(29)));

        TextView text = bodyText(label, 11, active ? COLOR_PRIMARY : COLOR_TEXT);
        text.setGravity(Gravity.CENTER);
        if (active) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20));
        textParams.topMargin = dp(1);
        item.addView(text, textParams);
        return item;
    }

    private Button chip(String label, boolean selected) {
        Button button = textButton(label, false);
        button.setTextSize(11);
        button.setTextColor(selected ? Color.WHITE : COLOR_TEXT);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(selected ? rounded(COLOR_PRIMARY, 18) : rounded(COLOR_SURFACE_2, 18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        params.rightMargin = dp(7);
        button.setLayoutParams(params);
        return button;
    }

    private Button primaryButton(String label) {
        Button button = textButton(label, false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setBackground(rounded(COLOR_PRIMARY, 16));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = textButton(label, false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setBackground(rounded(COLOR_PRIMARY_SOFT, 25));
        return button;
    }

    private Button secondaryOutlineButton(String label) {
        Button button = textButton(label, false);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(15);
        button.setBackground(outlined(Color.TRANSPARENT, COLOR_OUTLINE, 14));
        return button;
    }

    private Button textButton(String label, boolean allCaps) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(allCaps);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private EditText field(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setHintTextColor(COLOR_MUTED);
        input.setTextColor(COLOR_TEXT);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setPadding(dp(15), 0, dp(15), 0);
        input.setBackground(outlined(Color.TRANSPARENT, COLOR_OUTLINE, 8));
        return input;
    }

    private View formField(String label, View input) {
        LinearLayout block = vertical();
        TextView caption = bodyText(label, 14, COLOR_TEXT);
        caption.setGravity(Gravity.CENTER_VERTICAL);
        block.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(25)));
        block.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return block;
    }

    private LinearLayout.LayoutParams formFieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(73));
        params.topMargin = dp(8);
        return params;
    }

    private Button materialChoiceChip(String label, boolean selected) {
        Button button = textButton(label, false);
        button.setTextSize(11);
        button.setTextColor(selected ? Color.WHITE : COLOR_TEXT);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(selected
                ? rounded(COLOR_PRIMARY, 18)
                : outlined(Color.TRANSPARENT, COLOR_OUTLINE, 18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        params.rightMargin = dp(7);
        params.topMargin = dp(3);
        button.setLayoutParams(params);
        return button;
    }

    private TextView serifHeading(String value, int size) {
        TextView heading = bodyText(value, size, COLOR_TEXT);
        heading.setTypeface(Typeface.create("Noto Serif", Typeface.BOLD));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        return heading;
    }

    private View labeledControl(String label, View control) {
        FrameLayout block = new FrameLayout(this);
        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        controlParams.topMargin = dp(7);
        block.addView(control, controlParams);
        TextView caption = bodyText(label, 11, COLOR_PRIMARY);
        caption.setPadding(dp(4), 0, dp(4), 0);
        caption.setBackgroundColor(COLOR_BG);
        FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18));
        captionParams.leftMargin = dp(13);
        block.addView(caption, captionParams);
        return block;
    }

    private LinearLayout.LayoutParams labeledParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        params.topMargin = dp(7);
        return params;
    }

    private TextView heading(String value) {
        TextView heading = bodyText(value, 24, COLOR_TEXT);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        return heading;
    }

    private TextView bodyText(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private TextView iconText(String value, int size) {
        TextView icon = bodyText(value, size, COLOR_TEXT);
        icon.setGravity(Gravity.CENTER);
        icon.setBackgroundColor(Color.TRANSPARENT);
        icon.setClickable(true);
        return icon;
    }

    private View detailRow(String label, String value) {
        LinearLayout row = vertical();
        row.setPadding(dp(4), dp(12), dp(4), dp(12));
        row.addView(bodyText(label, 13, COLOR_MUTED), lpMatchWrap());
        TextView detail = bodyText(value, 16, COLOR_TEXT);
        LinearLayout.LayoutParams detailParams = lpMatchWrap();
        detailParams.topMargin = dp(3);
        row.addView(detail, detailParams);
        return row;
    }

    private void setArtworkImage(ImageView image, Artwork artwork) {
        try {
            image.setImageURI(Uri.parse(artwork.uri));
        } catch (Exception error) {
            image.setImageResource(R.drawable.app_icon);
        }
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable outlined(int color, int strokeColor, int radiusDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private GradientDrawable dashed(int color, int strokeColor, int radiusDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(1), strokeColor, dp(5), dp(4));
        return drawable;
    }

    private ImageView roundAction(int iconResource, String description) {
        ImageView button = new ImageView(this);
        button.setImageResource(iconResource);
        button.setColorFilter(COLOR_TEXT);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setClickable(true);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(COLOR_SURFACE_2);
        button.setBackground(circle);
        return button;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams lpWrapWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        params.topMargin = dp(12);
        return params;
    }

    private LinearLayout.LayoutParams detailParams() {
        LinearLayout.LayoutParams params = lpMatchWrap();
        params.topMargin = dp(3);
        return params;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = lpMatchWrap();
        params.bottomMargin = dp(10);
        return params;
    }

    private LinearLayout.LayoutParams navItemParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
    }

    private static final class Artwork {
        final String id;
        String title;
        String year;
        String material;
        String uri;

        Artwork(String id, String title, String year, String material, String uri) {
            this.id = id;
            this.title = title;
            this.year = year;
            this.material = material;
            this.uri = uri;
        }

        ArrayList<String> materialList() {
            ArrayList<String> values = new ArrayList<>();
            for (String value : material.split(" · ")) {
                String clean = value.trim();
                if (!clean.isEmpty()) values.add(clean);
            }
            return values;
        }
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }

    private interface MaterialAddedListener {
        void onAdded(String material);
    }

    private interface DraftChangedListener {
        void onChanged(String value);
    }

    private static final class DraftTextWatcher implements TextWatcher {
        private final DraftChangedListener listener;

        DraftTextWatcher(DraftChangedListener listener) {
            this.listener = listener;
        }

        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
            listener.onChanged(s.toString());
        }
        @Override public void afterTextChanged(Editable s) { }
    }

    private final class ArtworkAdapter extends BaseAdapter {
        private final Context context;
        private final ArrayList<Artwork> items;

        ArtworkAdapter(Context context, ArrayList<Artwork> items) {
            this.context = context;
            this.items = items;
        }

        @Override public int getCount() { return items.size(); }
        @Override public Artwork getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            CardHolder holder;
            if (convertView == null) {
                LinearLayout card = new LinearLayout(context);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(rounded(COLOR_SURFACE, 12));
                card.setElevation(dp(2));
                card.setClipToOutline(true);
                card.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(226)));

                FrameLayout imageWell = new FrameLayout(context);
                imageWell.setBackgroundColor(COLOR_SURFACE_2);

                ImageView blurredBackground = new ImageView(context);
                blurredBackground.setScaleType(ImageView.ScaleType.CENTER_CROP);
                blurredBackground.setAlpha(0.82f);
                imageWell.addView(blurredBackground, match());

                ImageView image = new ImageView(context);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                imageWell.addView(image, match());

                LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(168));
                card.addView(imageWell, imageParams);

                LinearLayout labels = new LinearLayout(context);
                labels.setOrientation(LinearLayout.VERTICAL);
                labels.setPadding(dp(9), dp(6), dp(9), dp(6));
                TextView title = bodyText("", 14, COLOR_TEXT);
                title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                title.setSingleLine(true);
                TextView sub = bodyText("", 11, COLOR_MUTED);
                sub.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                sub.setSingleLine(true);
                labels.addView(title, lpMatchWrap());
                LinearLayout.LayoutParams subParams = lpMatchWrap();
                subParams.topMargin = dp(1);
                labels.addView(sub, subParams);
                card.addView(labels, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

                holder = new CardHolder(blurredBackground, image, title, sub);
                card.setTag(holder);
                convertView = card;
            } else {
                holder = (CardHolder) convertView.getTag();
            }

            Artwork artwork = getItem(position);
            holder.title.setText(artwork.title);
            holder.sub.setText(artwork.year + "  ·  " + artwork.material);
            int thumbnailSide = Math.max(dp(168), (getResources().getDisplayMetrics().widthPixels - dp(42)) / 2);
            thumbnailLoader.bind(holder.image, holder.blurredBackground, artwork.uri, thumbnailSide);
            return convertView;
        }
    }

    private static final class CardHolder {
        final ImageView blurredBackground;
        final ImageView image;
        final TextView title;
        final TextView sub;

        CardHolder(ImageView blurredBackground, ImageView image, TextView title, TextView sub) {
            this.blurredBackground = blurredBackground;
            this.image = image;
            this.title = title;
            this.sub = sub;
        }
    }
}

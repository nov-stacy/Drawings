package com.example.drawingsarchive;

import android.app.AlertDialog;
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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends ComponentActivity {
    private static final int REQUEST_IMAGE = 1201;
    private static final int REQUEST_DOCUMENT_SCAN = 1202;
    private static final int REQUEST_BACKUP_FOLDER = 1301;
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
    private FrameLayout pageRoot;
    private Uri pendingImageUri;
    private ImageView pendingPreview;
    private TextView pendingPreviewHint;
    private final ArrayList<String> selectedArtworkMaterials = new ArrayList<>();
    private String searchQuery = "";
    private String currentScreen = "home";

    private Bitmap editorSourceBitmap;
    private ImageView editorPreview;
    private int editorRotation;
    private float editorAspect;
    private float editorBrightness;
    private float editorContrast = 1f;
    private int editorFilter;
    private Artwork editorTargetArtwork;

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
        loadData();
        DrawingBackup.schedule(this);
        new Thread(() -> DrawingBackup.writeIfDue(this, () -> false), "drawing-backup-check").start();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
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

        artworks.clear();
        try {
            JSONArray storedArtworks = new JSONArray(preferences.getString(KEY_ARTWORKS, "[]"));
            for (int i = 0; i < storedArtworks.length(); i++) {
                JSONObject item = storedArtworks.getJSONObject(i);
                Artwork artwork = new Artwork(
                        item.optString("title", "Без названия"),
                        item.optString("year", ""),
                        item.optString("material", ""),
                        item.optString("uri", "")
                );
                artworks.add(artwork);
            }
        } catch (Exception ignored) {
        }
    }

    private void saveData() {
        JSONArray artworkArray = new JSONArray();
        for (Artwork artwork : artworks) {
            JSONObject item = new JSONObject();
            try {
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

    private FrameLayout newPage() {
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
        currentScreen = "home";
        FrameLayout root = newPage();
        LinearLayout content = vertical();
        content.setPadding(dp(16), dp(10), dp(16), dp(82));
        root.addView(content, match());

        TextView heading = serifHeading("Мои рисунки", 28);
        content.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setText(searchQuery);
        search.setHint("Поиск");
        search.setHintTextColor(COLOR_MUTED);
        search.setTextColor(COLOR_TEXT);
        search.setTextSize(14);
        search.setPadding(dp(13), 0, dp(13), 0);
        search.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0);
        search.setCompoundDrawablePadding(dp(10));
        search.setBackground(rounded(COLOR_SURFACE_2, 14));
        content.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        Button allChip = chip("Все", selectedMaterialFilters.isEmpty());
        allChip.setOnClickListener(v -> {
            selectedMaterialFilters.clear();
            showHome();
        });
        chipRow.addView(allChip);
        for (String material : materials) {
            Button materialChip = chip(material, selectedMaterialFilters.contains(material));
            materialChip.setOnClickListener(v -> {
                selectedMaterialFilters.clear();
                selectedMaterialFilters.add(material);
                showHome();
            });
            chipRow.addView(materialChip);
        }
        chipScroll.addView(chipRow, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        LinearLayout.LayoutParams chipScrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        chipScrollParams.topMargin = dp(5);
        content.addView(chipScroll, chipScrollParams);

        FrameLayout collectionArea = new FrameLayout(this);
        content.addView(collectionArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        GridView grid = new GridView(this);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(10));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, dp(16));
        collectionArea.addView(grid, match());

        TextView empty = bodyText("Пока нет рисунков\nНажмите «＋», чтобы добавить первую работу", 15, COLOR_MUTED);
        empty.setGravity(Gravity.CENTER);
        collectionArea.addView(empty, match());
        grid.setEmptyView(empty);

        refreshVisible();
        grid.setAdapter(new ArtworkAdapter(this, visibleArtworks));
        grid.setOnItemClickListener((parent, view, position, id) -> showArtworkDetail(visibleArtworks.get(position)));

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
        addFloatingButton(root);
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
        currentScreen = "add";
        pendingImageUri = null;
        selectedArtworkMaterials.clear();
        if (materials.contains("Акварель")) selectedArtworkMaterials.add("Акварель");
        else if (!materials.isEmpty()) selectedArtworkMaterials.add(materials.get(0));
        FrameLayout root = newPage();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, match());

        LinearLayout content = vertical();
        content.setPadding(dp(18), dp(6), dp(18), dp(28));
        scroll.addView(content, lpMatchWrap());

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = iconText("‹", 34);
        back.setOnClickListener(v -> showHome());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(58)));
        TextView title = serifHeading("Новая работа", 23);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(58), 1));
        header.addView(new View(this), new LinearLayout.LayoutParams(dp(44), dp(58)));
        content.addView(header, lpMatchWrap());

        FrameLayout previewHolder = new FrameLayout(this);
        previewHolder.setBackground(dashed(COLOR_BG, COLOR_OUTLINE, 12));
        content.addView(previewHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(292)));

        pendingPreview = new ImageView(this);
        pendingPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pendingPreview.setImageResource(R.drawable.app_icon);
        pendingPreview.setBackground(rounded(COLOR_SURFACE_2, 10));
        pendingPreview.setClipToOutline(true);
        FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(224));
        previewParams.setMargins(dp(14), dp(14), dp(14), 0);
        previewHolder.addView(pendingPreview, previewParams);

        pendingPreviewHint = bodyText("Добавить рисунок", 14, COLOR_TEXT);
        pendingPreviewHint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50), Gravity.BOTTOM);
        hintParams.setMargins(dp(14), 0, dp(14), dp(4));
        previewHolder.addView(pendingPreviewHint, hintParams);

        TextView removeImage = iconText("×", 24);
        removeImage.setTextColor(Color.WHITE);
        removeImage.setBackground(rounded(Color.parseColor("#807D79"), 22));
        FrameLayout.LayoutParams removeParams = new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP | Gravity.END);
        removeParams.setMargins(0, dp(20), dp(20), 0);
        previewHolder.addView(removeImage, removeParams);
        removeImage.setOnClickListener(v -> {
            pendingImageUri = null;
            pendingPreview.setImageResource(R.drawable.app_icon);
        });
        previewHolder.setOnClickListener(v -> showImageSourceDialog());

        EditText titleInput = field("");
        titleInput.setText("Новая работа");
        EditText yearInput = field("");
        yearInput.setText(String.valueOf(Calendar.getInstance().get(Calendar.YEAR)));
        yearInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        content.addView(formField("Название", titleInput), formFieldParams());
        content.addView(formField("Год создания", yearInput), formFieldParams());

        TextView materialLabel = bodyText("Материал", 14, COLOR_TEXT);
        LinearLayout.LayoutParams materialLabelParams = lpMatchWrap();
        materialLabelParams.topMargin = dp(14);
        content.addView(materialLabel, materialLabelParams);
        HorizontalScrollView materialScroll = new HorizontalScrollView(this);
        materialScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout materialRow = new LinearLayout(this);
        materialRow.setOrientation(LinearLayout.HORIZONTAL);
        materialScroll.addView(materialRow, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)));
        content.addView(materialScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        renderArtworkMaterialChips(materialRow);

        Button save = primaryButton("Сохранить");
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        saveParams.topMargin = dp(14);
        content.addView(save, saveParams);
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
            try {
                year = Integer.parseInt(yearValue);
            } catch (NumberFormatException error) {
                yearInput.setError("Введите год цифрами");
                return;
            }
            if (year < 1000 || year > 2100) {
                yearInput.setError("Проверьте год");
                return;
            }
            if (titleValue.isEmpty()) titleValue = "Без названия";
            if (selectedArtworkMaterials.isEmpty()) {
                Toast.makeText(this, "Выберите хотя бы один материал", Toast.LENGTH_SHORT).show();
                return;
            }
            artworks.add(0, new Artwork(titleValue, yearValue, joinMaterials(selectedArtworkMaterials), pendingImageUri.toString()));
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
                selectedArtworkMaterials.clear();
                selectedArtworkMaterials.add(material);
                renderArtworkMaterialChips(row);
            });
            row.addView(option);
        }
        Button add = materialChoiceChip("＋ Добавить", false);
        add.setOnClickListener(v -> showAddMaterialDialog(material -> {
            selectedArtworkMaterials.clear();
            selectedArtworkMaterials.add(material);
            renderArtworkMaterialChips(row);
        }));
        row.addView(add);
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_IMAGE);
    }

    private void showImageSourceDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Добавить рисунок")
                .setItems(new String[]{"Сканировать рисунок", "Выбрать из галереи"}, (dialog, which) -> {
                    if (which == 0) scanWithCamera();
                    else chooseImage();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void scanWithCamera() {
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
                        startIntentSenderForResult(sender, REQUEST_DOCUMENT_SCAN, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException error) {
                        showScannerUnavailable();
                    }
                })
                .addOnFailureListener(this, error -> showScannerUnavailable());
    }

    private void showScannerUnavailable() {
        new AlertDialog.Builder(this)
                .setTitle("Сканер пока недоступен")
                .setMessage("Для первого запуска подключитесь к интернету и проверьте обновления сервисов Google Play. Можно повторить попытку или выбрать готовое фото.")
                .setNegativeButton("Закрыть", null)
                .setPositiveButton("Выбрать фото", (dialog, which) -> chooseImage())
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQUEST_BACKUP_FOLDER && data != null && data.getData() != null) {
            try {
                DrawingBackup.choose(this, data.getData());
                Toast.makeText(this, "Ежедневная копия включена", Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(this, "Нет доступа к папке. Выберите другую", Toast.LENGTH_LONG).show();
            }
            showSettings();
        } else if (requestCode == REQUEST_IMAGE && data != null && data.getData() != null) {
            Uri selected = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            editorTargetArtwork = null;
            showImageEditor(selected);
        } else if (requestCode == REQUEST_DOCUMENT_SCAN) {
            GmsDocumentScanningResult scanned = GmsDocumentScanningResult.fromActivityResultIntent(data);
            if (scanned == null || scanned.getPages() == null || scanned.getPages().size() != 1) {
                Toast.makeText(this, "Не удалось получить снимок. Попробуйте ещё раз", Toast.LENGTH_SHORT).show();
                return;
            }
            editorTargetArtwork = null;
            showImageEditor(scanned.getPages().get(0).getImageUri());
        }
    }

    private void showMaterialPicker(Button target) {
        boolean[] checked = new boolean[materials.size()];
        ArrayList<String> draft = new ArrayList<>(selectedArtworkMaterials);
        for (int i = 0; i < materials.size(); i++) checked[i] = draft.contains(materials.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Материалы")
                .setMultiChoiceItems(materials.toArray(new String[0]), checked, (dialog, which, isChecked) -> {
                    String material = materials.get(which);
                    if (isChecked && !draft.contains(material)) draft.add(material);
                    if (!isChecked) draft.remove(material);
                })
                .setNeutralButton("＋ Новый", (dialog, which) -> showAddMaterialDialog(material -> {
                    if (!selectedArtworkMaterials.contains(material)) selectedArtworkMaterials.add(material);
                    target.setText(selectedMaterialsLabel());
                }))
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Готово", (dialog, which) -> {
                    selectedArtworkMaterials.clear();
                    selectedArtworkMaterials.addAll(draft);
                    target.setText(selectedMaterialsLabel());
                })
                .show();
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
        EditText input = field("Например, пастель");
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
        Bitmap decoded = decodeBitmap(sourceUri, 2400);
        if (decoded == null) {
            Toast.makeText(this, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show();
            return;
        }
        editorSourceBitmap = decoded;
        editorRotation = 0;
        editorAspect = 0f;
        editorBrightness = 0f;
        editorContrast = 1f;
        editorFilter = 0;

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = vertical();
        content.setPadding(dp(18), dp(8), dp(18), dp(14));
        scroll.addView(content, lpMatchWrap());

        editorPreview = new ImageView(this);
        editorPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        editorPreview.setBackground(rounded(Color.parseColor("#E9DFDA"), 20));
        editorPreview.setClipToOutline(true);
        content.addView(editorPreview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));

        LinearLayout geometry = new LinearLayout(this);
        geometry.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams geometryParams = lpMatchWrap();
        geometryParams.topMargin = dp(12);
        content.addView(geometry, geometryParams);

        Button rotate = editorButton("↻  Повернуть");
        geometry.addView(rotate, new LinearLayout.LayoutParams(0, dp(48), 1));
        rotate.setOnClickListener(v -> {
            editorRotation = (editorRotation + 90) % 360;
            refreshEditorPreview();
        });

        Button crop = editorButton("Кадр: исходный");
        LinearLayout.LayoutParams cropParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        cropParams.leftMargin = dp(8);
        geometry.addView(crop, cropParams);
        crop.setOnClickListener(v -> {
            if (editorAspect == 0f) {
                editorAspect = 1f;
                crop.setText("Кадр: 1:1");
            } else if (editorAspect == 1f) {
                editorAspect = 0.8f;
                crop.setText("Кадр: 4:5");
            } else {
                editorAspect = 0f;
                crop.setText("Кадр: исходный");
            }
            refreshEditorPreview();
        });

        TextView filtersTitle = sectionLabel("Фильтр");
        LinearLayout.LayoutParams filtersTitleParams = lpMatchWrap();
        filtersTitleParams.topMargin = dp(18);
        content.addView(filtersTitle, filtersTitleParams);

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(filters, lpMatchWrap());
        String[] filterNames = {"Оригинал", "Ч/б", "Тёплый"};
        for (int i = 0; i < filterNames.length; i++) {
            final int mode = i;
            Button filter = editorButton(filterNames[i]);
            LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(0, dp(46), 1);
            if (i > 0) filterParams.leftMargin = dp(7);
            filters.addView(filter, filterParams);
            filter.setOnClickListener(v -> {
                editorFilter = mode;
                refreshEditorPreview();
            });
        }

        content.addView(sectionLabel("Яркость"), sliderLabelParams());
        SeekBar brightness = new SeekBar(this);
        brightness.setMax(200);
        brightness.setProgress(100);
        brightness.setProgressTintList(ColorStateList.valueOf(COLOR_PRIMARY));
        content.addView(brightness, lpMatchWrap());
        brightness.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                editorBrightness = (progress - 100) * 1.15f;
                updateEditorColorFilter();
            }
        });

        content.addView(sectionLabel("Контраст"), sliderLabelParams());
        SeekBar contrast = new SeekBar(this);
        contrast.setMax(200);
        contrast.setProgress(100);
        contrast.setProgressTintList(ColorStateList.valueOf(COLOR_PRIMARY));
        content.addView(contrast, lpMatchWrap());
        contrast.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                editorContrast = 0.45f + progress * 0.0105f;
                updateEditorColorFilter();
            }
        });

        refreshEditorPreview();
        AlertDialog editorDialog = new AlertDialog.Builder(this)
                .setTitle("Редактирование рисунка")
                .setView(scroll)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Готово", (dialog, which) -> saveEditorResult())
                .create();
        editorDialog.setOnDismissListener(dialog -> {
            editorPreview = null;
            editorSourceBitmap = null;
            editorTargetArtwork = null;
        });
        editorDialog.show();
    }

    private void refreshEditorPreview() {
        if (editorPreview == null || editorSourceBitmap == null) return;
        Bitmap preview = applyGeometry(editorSourceBitmap, editorRotation, editorAspect);
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

    private Bitmap applyGeometry(Bitmap source, int rotation, float aspect) {
        Bitmap rotated = source;
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        }
        if (aspect <= 0f) return rotated;
        int width = rotated.getWidth();
        int height = rotated.getHeight();
        float current = width / (float) height;
        int cropWidth = width;
        int cropHeight = height;
        if (current > aspect) cropWidth = Math.round(height * aspect);
        else cropHeight = Math.round(width / aspect);
        int left = Math.max(0, (width - cropWidth) / 2);
        int top = Math.max(0, (height - cropHeight) / 2);
        return Bitmap.createBitmap(rotated, left, top, cropWidth, cropHeight);
    }

    private void saveEditorResult() {
        if (editorSourceBitmap == null) return;
        try {
            Bitmap geometry = applyGeometry(editorSourceBitmap, editorRotation, editorAspect);
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
                editorTargetArtwork.uri = pendingImageUri.toString();
                saveData();
                Artwork updated = editorTargetArtwork;
                editorTargetArtwork = null;
                Toast.makeText(this, "Изменения сохранены", Toast.LENGTH_SHORT).show();
                showArtworkDetail(updated);
            } else {
                if (pendingPreview != null) pendingPreview.setImageURI(pendingImageUri);
                if (pendingPreviewHint != null) pendingPreviewHint.setText("Изменить рисунок");
                Toast.makeText(this, "Рисунок готов", Toast.LENGTH_SHORT).show();
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
        currentScreen = "detail";
        FrameLayout root = newPage();
        LinearLayout page = vertical();
        page.setPadding(dp(18), dp(4), dp(18), dp(12));
        root.addView(page, match());

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = iconText("‹", 30);
        back.setOnClickListener(v -> showHome());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        header.addView(new View(this), new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView remove = iconText("⋮", 26);
        remove.setContentDescription("Действия");
        remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setItems(new String[]{"Удалить работу"}, (dialog, which) -> confirmDelete(artwork))
                .show());
        header.addView(remove, new LinearLayout.LayoutParams(dp(44), dp(48)));
        page.addView(header, lpMatchWrap());

        TextView title = serifHeading(artwork.title, 27);
        title.setSingleLine(true);
        page.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = vertical();
        scroll.addView(content, lpMatchWrap());
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(rounded(COLOR_SURFACE_2, 10));
        image.setClipToOutline(true);
        setArtworkImage(image, artwork);
        content.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(372)));

        View divider = new View(this);
        divider.setBackgroundColor(COLOR_OUTLINE);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(18);
        content.addView(divider, dividerParams);
        content.addView(detailLine("Год создания", artwork.year), lpMatchWrap());
        content.addView(detailLine("Материал", artwork.material), lpMatchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        ImageView edit = roundAction(R.drawable.ic_action_edit, "Редактировать изображение");
        edit.setOnClickListener(v -> {
            editorTargetArtwork = artwork;
            showImageEditor(Uri.parse(artwork.uri));
        });
        actions.addView(edit, new LinearLayout.LayoutParams(dp(58), dp(58)));
        ImageView share = roundAction(R.drawable.ic_action_share, "Поделиться");
        share.setOnClickListener(v -> shareArtwork(artwork));
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(dp(58), dp(58));
        shareParams.leftMargin = dp(46);
        actions.addView(share, shareParams);
        page.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));
    }

    private View detailLine(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView left = bodyText(label, 14, COLOR_MUTED);
        TextView right = bodyText(value, 15, COLOR_TEXT);
        row.addView(left, new LinearLayout.LayoutParams(dp(126), dp(50)));
        left.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(right, new LinearLayout.LayoutParams(0, dp(50), 1));
        right.setGravity(Gravity.CENTER_VERTICAL);
        View line = new View(this);
        line.setBackgroundColor(COLOR_OUTLINE);
        LinearLayout wrapper = vertical();
        wrapper.addView(row, lpMatchWrap());
        wrapper.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return wrapper;
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
        page.addView(serifHeading("По годам", 28), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

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
            ArrayList<Artwork> sorted = new ArrayList<>(artworks);
            Collections.sort(sorted, (left, right) -> right.year.compareTo(left.year));
            Map<String, List<Artwork>> grouped = new LinkedHashMap<>();
            for (Artwork artwork : sorted) {
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
        addFloatingButton(root);
    }

    private View yearThumbnail(Artwork artwork) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(rounded(COLOR_SURFACE_2, 13));
        image.setClipToOutline(true);
        setArtworkImage(image, artwork);
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
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(rounded(COLOR_SURFACE_2, 12));
        image.setClipToOutline(true);
        setArtworkImage(image, artwork);
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
        page.setPadding(dp(18), dp(12), dp(18), dp(86));
        scroll.addView(page, lpMatchWrap());
        root.addView(scroll, match());
        page.addView(serifHeading("Настройки", 28), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

        TextView backupSection = bodyText("Ежедневная резервная копия", 14, COLOR_PRIMARY);
        backupSection.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams backupSectionParams = lpMatchWrap();
        backupSectionParams.topMargin = dp(18);
        page.addView(backupSection, backupSectionParams);

        boolean backupEnabled = DrawingBackup.enabled(this);
        String backupMessage = backupEnabled
                ? "Папка: " + DrawingBackup.folderName(this) + "\n" + DrawingBackup.status(this)
                : "Копия коллекции и рисунков будет создаваться раз в сутки.";
        TextView backupStatus = bodyText(backupMessage, 14, COLOR_MUTED);
        backupStatus.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams backupStatusParams = lpMatchWrap();
        backupStatusParams.topMargin = dp(9);
        page.addView(backupStatus, backupStatusParams);

        Button chooseBackup = secondaryButton(backupEnabled ? "Изменить папку" : "Выбрать папку");
        LinearLayout.LayoutParams chooseBackupParams = fieldParams();
        chooseBackupParams.topMargin = dp(12);
        page.addView(chooseBackup, chooseBackupParams);
        chooseBackup.setOnClickListener(v -> openBackupFolderPicker());

        if (backupEnabled) {
            Button disableBackup = textButton("Выключить ежедневную копию", false);
            disableBackup.setTextSize(14);
            disableBackup.setTextColor(COLOR_TEXT);
            disableBackup.setBackground(outlined(Color.TRANSPARENT, COLOR_OUTLINE, 25));
            LinearLayout.LayoutParams disableParams = fieldParams();
            disableParams.topMargin = dp(8);
            page.addView(disableBackup, disableParams);
            disableBackup.setOnClickListener(v -> {
                DrawingBackup.disable(this);
                showSettings();
            });
        }

        TextView section = bodyText("Материалы", 14, COLOR_PRIMARY);
        section.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams sectionParams = lpMatchWrap();
        sectionParams.topMargin = dp(28);
        page.addView(section, sectionParams);

        LinearLayout materialList = vertical();
        page.addView(materialList, lpMatchWrap());
        renderMaterialList(materialList);

        Button add = secondaryButton("＋ Добавить материал");
        LinearLayout.LayoutParams addParams = fieldParams();
        addParams.topMargin = dp(12);
        page.addView(add, addParams);
        add.setOnClickListener(v -> showAddMaterialDialog(material -> renderMaterialList(materialList)));

        addBottomNavigation(root, "Настройки");
    }

    private void openBackupFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_BACKUP_FOLDER);
    }

    private void renderMaterialList(LinearLayout list) {
        list.removeAllViews();
        for (String material : materials) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(7), 0);
            row.setBackground(rounded(COLOR_SURFACE, 14));

            TextView name = bodyText(material, 16, COLOR_TEXT);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView remove = iconText("×", 25);
            remove.setContentDescription("Удалить материал " + material);
            remove.setTextColor(COLOR_MUTED);
            remove.setOnClickListener(v -> confirmDeleteMaterial(material, list));
            row.addView(remove, new LinearLayout.LayoutParams(dp(44), dp(44)));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            params.topMargin = dp(8);
            list.addView(row, params);
        }
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

    private void addBottomNavigation(FrameLayout root, String active) {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(20), dp(8), dp(20), dp(4));
        nav.setBackgroundColor(COLOR_BG);

        boolean collectionActive = active.equals("Коллекция") || active.equals("По годам");
        View collection = navItem(R.drawable.ic_nav_works, "Коллекция", collectionActive);
        collection.setOnClickListener(v -> {
            if (active.equals("Коллекция")) showYears();
            else showHome();
        });
        nav.addView(collection, navItemParams());

        nav.addView(new View(this), navItemParams());

        View settings = navItem(R.drawable.ic_nav_settings, "Настройки", active.equals("Настройки"));
        settings.setOnClickListener(v -> showSettings());
        nav.addView(settings, navItemParams());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78), Gravity.BOTTOM);
        root.addView(nav, params);
    }

    private void addFloatingButton(FrameLayout root) {
        ImageView add = new ImageView(this);
        add.setImageResource(R.drawable.ic_action_add);
        add.setColorFilter(Color.WHITE);
        add.setScaleType(ImageView.ScaleType.CENTER);
        add.setContentDescription("Добавить рисунок");
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(COLOR_PRIMARY);
        add.setBackground(circle);
        add.setElevation(dp(7));
        add.setOnClickListener(v -> showAddArtwork());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.setMargins(0, 0, 0, dp(18));
        root.addView(add, params);
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

    private View formField(String label, EditText input) {
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
        final String title;
        final String year;
        final String material;
        String uri;

        Artwork(String title, String year, String material, String uri) {
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
                card.setBackground(rounded(COLOR_SURFACE, 10));
                card.setElevation(dp(1));
                card.setClipToOutline(true);
                card.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(204)));

                ImageView image = new ImageView(context);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(154)));

                LinearLayout labels = new LinearLayout(context);
                labels.setOrientation(LinearLayout.VERTICAL);
                labels.setPadding(dp(8), dp(5), dp(8), dp(5));
                TextView title = bodyText("", 14, COLOR_TEXT);
                title.setSingleLine(true);
                TextView sub = bodyText("", 11, COLOR_MUTED);
                labels.addView(title, lpMatchWrap());
                LinearLayout.LayoutParams subParams = lpMatchWrap();
                subParams.topMargin = dp(1);
                labels.addView(sub, subParams);
                card.addView(labels, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

                holder = new CardHolder(image, title, sub);
                card.setTag(holder);
                convertView = card;
            } else {
                holder = (CardHolder) convertView.getTag();
            }

            Artwork artwork = getItem(position);
            holder.title.setText(artwork.year);
            holder.sub.setText(artwork.material);
            setArtworkImage(holder.image, artwork);
            return convertView;
        }
    }

    private static final class CardHolder {
        final ImageView image;
        final TextView title;
        final TextView sub;

        CardHolder(ImageView image, TextView title, TextView sub) {
            this.image = image;
            this.title = title;
            this.sub = sub;
        }
    }
}

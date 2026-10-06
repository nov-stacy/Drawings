package com.example.drawingsarchive;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
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
import android.provider.MediaStore;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQUEST_IMAGE = 1201;
    private static final int REQUEST_CAMERA = 1202;
    private static final String PREFS = "drawings_archive";
    private static final String KEY_ARTWORKS = "artworks";
    private static final String KEY_MATERIALS = "materials";

    private final int COLOR_BG = Color.parseColor("#FFF8F5");
    private final int COLOR_SURFACE = Color.parseColor("#FFFDFB");
    private final int COLOR_SURFACE_2 = Color.parseColor("#F6EBE6");
    private final int COLOR_PRIMARY = Color.parseColor("#8D4F3A");
    private final int COLOR_PRIMARY_SOFT = Color.parseColor("#FFDBCE");
    private final int COLOR_TEXT = Color.parseColor("#241915");
    private final int COLOR_MUTED = Color.parseColor("#786762");
    private final int COLOR_OUTLINE = Color.parseColor("#D9C5BD");

    private final ArrayList<Artwork> artworks = new ArrayList<>();
    private final ArrayList<String> materials = new ArrayList<>();
    private final ArrayList<Artwork> visibleArtworks = new ArrayList<>();
    private final ArrayList<String> selectedMaterialFilters = new ArrayList<>();

    private SharedPreferences preferences;
    private FrameLayout pageRoot;
    private Uri pendingImageUri;
    private Uri pendingCameraUri;
    private ImageView pendingPreview;
    private TextView pendingPreviewHint;
    private final ArrayList<String> selectedArtworkMaterials = new ArrayList<>();
    private String searchQuery = "";

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
        getWindow().setNavigationBarColor(COLOR_SURFACE_2);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            getWindow().setAttributes(attributes);
        }
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadData();
        showHome();
    }

    private void loadData() {
        materials.clear();
        materials.addAll(Arrays.asList("Акварель", "Карандаш", "Маркеры"));
        try {
            JSONArray storedMaterials = new JSONArray(preferences.getString(KEY_MATERIALS, "[]"));
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
            if (!material.equals("Акварель") && !material.equals("Карандаш") && !material.equals("Маркеры")) {
                materialArray.put(material);
            }
        }

        preferences.edit()
                .putString(KEY_ARTWORKS, artworkArray.toString())
                .putString(KEY_MATERIALS, materialArray.toString())
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
        FrameLayout root = newPage();
        LinearLayout content = vertical();
        content.setPadding(dp(16), dp(12), dp(16), dp(86));
        root.addView(content, match());

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = heading("Мои рисунки");
        topBar.addView(heading, new LinearLayout.LayoutParams(0, dp(58), 1));
        Button viewButton = textButton("▦", false);
        viewButton.setTextSize(24);
        viewButton.setTextColor(COLOR_TEXT);
        viewButton.setBackgroundColor(Color.TRANSPARENT);
        viewButton.setContentDescription("Вид коллекции");
        topBar.addView(viewButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(topBar, lpMatchWrap());

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setText(searchQuery);
        search.setHint("Поиск по названию, году…");
        search.setHintTextColor(COLOR_MUTED);
        search.setTextColor(COLOR_TEXT);
        search.setTextSize(16);
        search.setPadding(dp(18), 0, dp(18), 0);
        search.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0);
        search.setCompoundDrawablePadding(dp(10));
        search.setBackground(rounded(COLOR_SURFACE_2, 28));
        content.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

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
                if (selectedMaterialFilters.contains(material)) selectedMaterialFilters.remove(material);
                else selectedMaterialFilters.add(material);
                showHome();
            });
            chipRow.addView(materialChip);
        }
        chipScroll.addView(chipRow, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        LinearLayout.LayoutParams chipScrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        chipScrollParams.topMargin = dp(4);
        content.addView(chipScroll, chipScrollParams);

        FrameLayout collectionArea = new FrameLayout(this);
        content.addView(collectionArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        GridView grid = new GridView(this);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(12));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, dp(16));
        collectionArea.addView(grid, match());

        TextView empty = bodyText("Пока нет рисунков\nНажмите «＋», чтобы добавить первую работу", 16, COLOR_MUTED);
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

        addBottomNavigation(root, "Работы");
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
        pendingImageUri = null;
        selectedArtworkMaterials.clear();
        if (!materials.isEmpty()) selectedArtworkMaterials.add(materials.get(0));
        FrameLayout root = newPage();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, match());

        LinearLayout content = vertical();
        content.setPadding(dp(18), dp(8), dp(18), dp(28));
        scroll.addView(content, lpMatchWrap());

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = iconText("×", 28);
        back.setTranslationY(-dp(6));
        back.setOnClickListener(v -> showHome());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(58)));
        TextView title = bodyText("Новая работа", 20, COLOR_TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, dp(58), 1));
        header.addView(new View(this), new LinearLayout.LayoutParams(dp(44), dp(58)));
        content.addView(header, lpMatchWrap());

        FrameLayout previewHolder = new FrameLayout(this);
        previewHolder.setBackground(rounded(COLOR_SURFACE_2, 22));
        previewHolder.setClipToOutline(true);
        content.addView(previewHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(188)));

        pendingPreview = new ImageView(this);
        pendingPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pendingPreview.setImageResource(R.drawable.app_icon);
        previewHolder.addView(pendingPreview, match());

        pendingPreviewHint = bodyText("Добавить рисунок", 13, COLOR_TEXT);
        pendingPreviewHint.setGravity(Gravity.CENTER);
        pendingPreviewHint.setPadding(dp(14), dp(7), dp(14), dp(7));
        pendingPreviewHint.setBackground(rounded(Color.argb(225, 255, 255, 255), 18));
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38), Gravity.CENTER);
        previewHolder.addView(pendingPreviewHint, hintParams);
        previewHolder.setOnClickListener(v -> showImageSourceDialog());

        EditText titleInput = field("");
        EditText yearInput = field("");
        yearInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        content.addView(labeledControl("Название", titleInput), labeledParams());
        content.addView(labeledControl("Год создания", yearInput), labeledParams());

        Button materialButton = secondaryButton(selectedMaterialsLabel());
        materialButton.setTextColor(COLOR_TEXT);
        materialButton.setBackground(outlined(Color.TRANSPARENT, COLOR_OUTLINE, 12));
        materialButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        materialButton.setPadding(dp(16), 0, dp(16), 0);
        content.addView(labeledControl("Материал", materialButton), labeledParams());
        materialButton.setOnClickListener(v -> showMaterialPicker(materialButton));

        Button save = primaryButton("Сохранить");
        LinearLayout.LayoutParams saveParams = fieldParams();
        saveParams.topMargin = dp(18);
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
                .setItems(new String[]{"Сканировать камерой", "Выбрать из галереи"}, (dialog, which) -> {
                    if (which == 0) scanWithCamera();
                    else chooseImage();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void scanWithCamera() {
        File directory = new File(getCacheDir(), "captures");
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(this, "Не удалось подготовить камеру", Toast.LENGTH_SHORT).show();
            return;
        }
        File photo = new File(directory, "scan_" + System.currentTimeMillis() + ".jpg");
        pendingCameraUri = CaptureFileProvider.uriForFile(this, photo);
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
        intent.setClipData(ClipData.newRawUri("Рисунок", pendingCameraUri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (intent.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "На телефоне не найдено приложение камеры", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivityForResult(intent, REQUEST_CAMERA);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQUEST_IMAGE && data != null && data.getData() != null) {
            Uri selected = data.getData();
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            try {
                getContentResolver().takePersistableUriPermission(selected, flags);
            } catch (Exception ignored) {
            }
            editorTargetArtwork = null;
            showImageEditor(selected);
        } else if (requestCode == REQUEST_CAMERA && pendingCameraUri != null) {
            editorTargetArtwork = null;
            showImageEditor(pendingCameraUri);
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
        FrameLayout root = newPage();
        LinearLayout page = vertical();
        page.setPadding(dp(18), dp(4), dp(18), dp(24));
        root.addView(page, match());

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = iconText("‹", 30);
        back.setTranslationY(-dp(5));
        back.setOnClickListener(v -> showHome());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(60)));
        TextView title = bodyText(artwork.title, 20, COLOR_TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(60), 1));
        TextView remove = iconText("⋮", 26);
        remove.setContentDescription("Действия");
        remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setItems(new String[]{"Редактировать изображение", "Удалить работу"}, (dialog, which) -> {
                    if (which == 0) {
                        editorTargetArtwork = artwork;
                        showImageEditor(Uri.parse(artwork.uri));
                    } else {
                        confirmDelete(artwork);
                    }
                })
                .show());
        header.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(60)));
        page.addView(header, lpMatchWrap());

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = vertical();
        scroll.addView(content, lpMatchWrap());
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(rounded(COLOR_SURFACE_2, 20));
        image.setClipToOutline(true);
        setArtworkImage(image, artwork);
        content.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));

        content.addView(detailRow("Год создания", artwork.year), detailParams());
        content.addView(detailRow("Материалы", artwork.material), detailParams());
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

    private void showYears() {
        FrameLayout root = newPage();
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), dp(86));
        root.addView(page, match());
        page.addView(heading("По годам"), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = vertical();
        list.setPadding(0, dp(12), 0, dp(18));
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
                TextView year = bodyText(group.getKey(), 24, COLOR_TEXT);
                year.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                TextView count = bodyText(group.getValue().size() + " работ", 14, COLOR_MUTED);
                yearHeader.addView(year, new LinearLayout.LayoutParams(0, dp(48), 1));
                yearHeader.addView(count, lpWrapWrap());
                list.addView(yearHeader, lpMatchWrap());
                HorizontalScrollView stripScroll = new HorizontalScrollView(this);
                stripScroll.setHorizontalScrollBarEnabled(false);
                LinearLayout strip = new LinearLayout(this);
                strip.setOrientation(LinearLayout.HORIZONTAL);
                stripScroll.addView(strip, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                for (Artwork artwork : group.getValue()) strip.addView(yearThumbnail(artwork));
                LinearLayout.LayoutParams stripParams = lpMatchWrap();
                stripParams.bottomMargin = dp(20);
                list.addView(stripScroll, stripParams);
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(88), dp(116));
        params.rightMargin = dp(8);
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
        FrameLayout root = newPage();
        LinearLayout page = vertical();
        page.setPadding(dp(18), dp(12), dp(18), dp(86));
        root.addView(page, match());
        page.addView(heading("Настройки"), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        TextView section = bodyText("Материалы", 14, COLOR_PRIMARY);
        section.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams sectionParams = lpMatchWrap();
        sectionParams.topMargin = dp(18);
        page.addView(section, sectionParams);

        LinearLayout materialList = vertical();
        page.addView(materialList, lpMatchWrap());
        renderMaterialList(materialList);

        Button add = secondaryButton("＋ Добавить материал");
        LinearLayout.LayoutParams addParams = fieldParams();
        addParams.topMargin = dp(12);
        page.addView(add, addParams);
        add.setOnClickListener(v -> showAddMaterialDialog(material -> renderMaterialList(materialList)));

        TextView storage = bodyText("Данные хранятся только на этом устройстве. Рисунок можно выбрать из галереи или отсканировать камерой.", 14, COLOR_MUTED);
        LinearLayout.LayoutParams storageParams = lpMatchWrap();
        storageParams.topMargin = dp(28);
        page.addView(storage, storageParams);

        addBottomNavigation(root, "Настройки");
    }

    private void renderMaterialList(LinearLayout list) {
        list.removeAllViews();
        for (String material : materials) {
            TextView row = bodyText(material, 16, COLOR_TEXT);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setBackground(rounded(COLOR_SURFACE, 14));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            params.topMargin = dp(8);
            list.addView(row, params);
        }
    }

    private void addBottomNavigation(FrameLayout root, String active) {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(5), dp(8), dp(5));
        nav.setBackgroundColor(COLOR_SURFACE_2);

        View works = navItem("▦", "Работы", active.equals("Работы"));
        works.setOnClickListener(v -> showHome());
        nav.addView(works, navItemParams());

        View years = navItem("▣", "По годам", active.equals("По годам"));
        years.setOnClickListener(v -> showYears());
        nav.addView(years, navItemParams());

        View settings = navItem("⚙", "Настройки", active.equals("Настройки"));
        settings.setOnClickListener(v -> showSettings());
        nav.addView(settings, navItemParams());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68), Gravity.BOTTOM);
        root.addView(nav, params);
    }

    private void addFloatingButton(FrameLayout root) {
        Button add = textButton("＋", false);
        add.setTextSize(29);
        add.setTextColor(COLOR_TEXT);
        add.setContentDescription("Добавить рисунок");
        add.setBackground(rounded(COLOR_PRIMARY_SOFT, 17));
        add.setElevation(dp(7));
        add.setOnClickListener(v -> showAddArtwork());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.END);
        params.setMargins(0, 0, dp(19), dp(88));
        root.addView(add, params);
    }

    private View navItem(String symbol, String label, boolean active) {
        LinearLayout item = vertical();
        item.setGravity(Gravity.CENTER);
        item.setBackgroundColor(Color.TRANSPARENT);
        item.setClickable(true);

        TextView icon = bodyText(symbol, 17, active ? COLOR_TEXT : COLOR_MUTED);
        icon.setGravity(Gravity.CENTER);
        if (active) icon.setBackground(rounded(COLOR_PRIMARY_SOFT, 18));
        item.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(29)));

        TextView text = bodyText(label, 11, active ? COLOR_TEXT : COLOR_MUTED);
        text.setGravity(Gravity.CENTER);
        if (active) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20));
        textParams.topMargin = dp(1);
        item.addView(text, textParams);
        return item;
    }

    private Button chip(String label, boolean selected) {
        Button button = textButton(label, false);
        button.setTextSize(13);
        button.setTextColor(COLOR_TEXT);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(selected ? rounded(COLOR_PRIMARY_SOFT, 11) : outlined(Color.TRANSPARENT, COLOR_OUTLINE, 11));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        params.rightMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private Button primaryButton(String label) {
        Button button = textButton(label, false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setBackground(rounded(COLOR_PRIMARY, 25));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = textButton(label, false);
        button.setTextColor(COLOR_TEXT);
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
        input.setBackground(outlined(Color.TRANSPARENT, COLOR_OUTLINE, 12));
        return input;
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
                card.setBackground(rounded(COLOR_SURFACE, 16));
                card.setElevation(dp(3));
                card.setClipToOutline(true);
                card.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));

                ImageView image = new ImageView(context);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(142)));

                LinearLayout labels = new LinearLayout(context);
                labels.setOrientation(LinearLayout.VERTICAL);
                labels.setPadding(dp(14), dp(11), dp(14), dp(13));
                TextView title = bodyText("", 15, COLOR_TEXT);
                title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                title.setSingleLine(true);
                TextView sub = bodyText("", 13, COLOR_MUTED);
                labels.addView(title, lpMatchWrap());
                LinearLayout.LayoutParams subParams = lpMatchWrap();
                subParams.topMargin = dp(5);
                labels.addView(sub, subParams);
                card.addView(labels, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

                holder = new CardHolder(image, title, sub);
                card.setTag(holder);
                convertView = card;
            } else {
                holder = (CardHolder) convertView.getTag();
            }

            Artwork artwork = getItem(position);
            holder.title.setText(artwork.title);
            holder.sub.setText(artwork.year + " · " + artwork.material);
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

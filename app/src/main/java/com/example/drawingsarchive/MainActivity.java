package com.example.drawingsarchive;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private static final String PREFS = "drawings_archive";
    private static final String KEY_ARTWORKS = "artworks";
    private static final String KEY_MATERIALS = "materials";

    private final int COLOR_BG = Color.parseColor("#FFF8F5");
    private final int COLOR_SURFACE = Color.parseColor("#FFFDFB");
    private final int COLOR_SURFACE_2 = Color.parseColor("#F6EBE6");
    private final int COLOR_PRIMARY = Color.parseColor("#8D4F3A");
    private final int COLOR_PRIMARY_SOFT = Color.parseColor("#8B7568");
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
    private ImageView pendingPreview;
    private String selectedMaterial = "Акварель";
    private String searchQuery = "";

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

        TextView heading = heading("Мои рисунки");
        content.addView(heading, lpMatchWrap());

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams searchRowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        searchRowParams.topMargin = dp(10);
        content.addView(searchRow, searchRowParams);

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
        searchRow.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        Button settingsButton = textButton("⚙", false);
        settingsButton.setTextSize(23);
        settingsButton.setTextColor(Color.WHITE);
        settingsButton.setContentDescription("Настройки");
        settingsButton.setBackground(rounded(COLOR_PRIMARY_SOFT, 18));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        settingsParams.leftMargin = dp(9);
        searchRow.addView(settingsButton, settingsParams);
        settingsButton.setOnClickListener(v -> showSettings());

        Button materialFilter = secondaryButton(materialFilterLabel());
        materialFilter.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        materialFilter.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        filterParams.topMargin = dp(12);
        filterParams.bottomMargin = dp(12);
        content.addView(materialFilter, filterParams);
        materialFilter.setOnClickListener(v -> showMaterialFilterDialog());

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
            boolean filterMatches = selectedMaterialFilters.isEmpty() || selectedMaterialFilters.contains(artwork.material);
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
        selectedMaterial = materials.isEmpty() ? "Акварель" : materials.get(0);
        FrameLayout root = newPage();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, match());

        LinearLayout content = vertical();
        content.setPadding(dp(18), dp(8), dp(18), dp(28));
        scroll.addView(content, lpMatchWrap());

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = textButton("‹", false);
        back.setTextSize(30);
        back.setOnClickListener(v -> showHome());
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView title = bodyText("Новая работа", 23, COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(58), 1));
        content.addView(header, lpMatchWrap());

        pendingPreview = new ImageView(this);
        pendingPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pendingPreview.setImageResource(R.drawable.app_icon);
        pendingPreview.setBackground(rounded(COLOR_SURFACE_2, 22));
        pendingPreview.setClipToOutline(true);
        content.addView(pendingPreview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));

        Button choose = secondaryButton("Выбрать рисунок");
        LinearLayout.LayoutParams chooseParams = lpMatchWrap();
        chooseParams.topMargin = dp(10);
        content.addView(choose, chooseParams);
        choose.setOnClickListener(v -> chooseImage());

        EditText titleInput = field("Название");
        EditText yearInput = field("Год создания");
        yearInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        content.addView(titleInput, fieldParams());
        content.addView(yearInput, fieldParams());

        Button materialButton = secondaryButton("Материал: " + selectedMaterial);
        content.addView(materialButton, fieldParams());
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
            artworks.add(0, new Artwork(titleValue, yearValue, selectedMaterial, pendingImageUri.toString()));
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        pendingImageUri = data.getData();
        int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(pendingImageUri, flags);
        } catch (Exception ignored) {
        }
        if (pendingPreview != null) pendingPreview.setImageURI(pendingImageUri);
    }

    private void showMaterialPicker(Button target) {
        ArrayList<String> options = new ArrayList<>(materials);
        options.add("＋ Добавить материал");
        new AlertDialog.Builder(this)
                .setTitle("Материал")
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    if (which == options.size() - 1) {
                        showAddMaterialDialog(material -> {
                            selectedMaterial = material;
                            target.setText("Материал: " + material);
                        });
                    } else {
                        selectedMaterial = options.get(which);
                        target.setText("Материал: " + selectedMaterial);
                    }
                })
                .show();
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

    private void showArtworkDetail(Artwork artwork) {
        LinearLayout content = vertical();
        content.setPadding(dp(18), dp(6), dp(18), 0);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(rounded(COLOR_SURFACE_2, 20));
        image.setClipToOutline(true);
        setArtworkImage(image, artwork);
        content.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));

        content.addView(detailRow("Год создания", artwork.year), detailParams());
        content.addView(detailRow("Материал", artwork.material), detailParams());

        new AlertDialog.Builder(this)
                .setTitle(artwork.title)
                .setView(content)
                .setNegativeButton("Удалить", (dialog, which) -> confirmDelete(artwork))
                .setPositiveButton("Закрыть", null)
                .show();
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
        page.addView(heading("По годам"), lpMatchWrap());

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
                for (Artwork artwork : group.getValue()) list.addView(yearRow(artwork), rowParams());
            }
        }
        addBottomNavigation(root, "По годам");
        addFloatingButton(root);
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
        page.addView(heading("Настройки"), lpMatchWrap());

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

        TextView storage = bodyText("Данные хранятся только на этом устройстве. Рисунки выбираются через системную галерею.", 14, COLOR_MUTED);
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

        Button works = navButton("Работы", active.equals("Работы"));
        works.setOnClickListener(v -> showHome());
        nav.addView(works, navItemParams());

        Button years = navButton("По годам", active.equals("По годам"));
        years.setOnClickListener(v -> showYears());
        nav.addView(years, navItemParams());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68), Gravity.BOTTOM);
        root.addView(nav, params);
    }

    private void addFloatingButton(FrameLayout root) {
        Button add = textButton("＋", false);
        add.setTextSize(29);
        add.setTextColor(Color.WHITE);
        add.setContentDescription("Добавить рисунок");
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(COLOR_PRIMARY_SOFT);
        add.setBackground(circle);
        add.setElevation(dp(7));
        add.setOnClickListener(v -> showAddArtwork());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM | Gravity.END);
        params.setMargins(0, 0, dp(20), dp(82));
        root.addView(add, params);
    }

    private Button navButton(String label, boolean active) {
        Button button = textButton(label, false);
        button.setTextSize(12);
        button.setTextColor(active ? COLOR_TEXT : COLOR_MUTED);
        if (active) button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
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
        button.setBackgroundTintList(ColorStateList.valueOf(COLOR_PRIMARY));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = textButton(label, false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setBackgroundTintList(ColorStateList.valueOf(COLOR_PRIMARY_SOFT));
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

    private TextView heading(String value) {
        TextView heading = bodyText(value, 28, COLOR_TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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
        final String uri;

        Artwork(String title, String year, String material, String uri) {
            this.title = title;
            this.year = year;
            this.material = material;
            this.uri = uri;
        }
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
                card.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(248)));

                ImageView image = new ImageView(context);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(172)));

                LinearLayout labels = new LinearLayout(context);
                labels.setOrientation(LinearLayout.VERTICAL);
                labels.setPadding(dp(14), dp(11), dp(14), dp(13));
                TextView title = bodyText("", 15, COLOR_TEXT);
                title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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

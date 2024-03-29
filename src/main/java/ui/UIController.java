package ui;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import datamodel.DataModel;
import datamodel.Enums;
import datamodel.Serializer;
import datamodel.objects.Collection;
import datamodel.objects.Skin;
import datamodel.objects.*;
import datamodel.parser.parsestrategies.ParseException;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.text.Text;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class UIController {

    DataModel model;
    String filter;
    boolean useRPathFlag;
    boolean hasExportedFlag;
    List<String> selectedPathsToParse;
    List<String> failedParsePaths;
    Set<String> selectedPathsToMerge;
    Deque<MergeMemento> mementos;
    ObservableList<String> logs;
    ObservableList<IntegerProperty> changedObjectSizes;

    //    String benchFilter = "_interactive";
//    String stringFilter = "prefabs_";
    public UIController() {
        model = new DataModel();
        filter = "";
        selectedPathsToParse = new ArrayList<>();
        failedParsePaths = new ArrayList<>();
        mementos = new ArrayDeque<>();
        selectedPathsToMerge = new HashSet<>();
        logs = FXCollections.observableArrayList();

        changedObjectSizes = FXCollections.observableArrayList(p -> new Observable[]{p});

        IntegerProperty bSize = new SimpleIntegerProperty(0);
        IntegerProperty cSize = new SimpleIntegerProperty(0);
        IntegerProperty ciSize = new SimpleIntegerProperty(0);
        IntegerProperty gstSize = new SimpleIntegerProperty(0);
        IntegerProperty iSize = new SimpleIntegerProperty(0);
        IntegerProperty pSize = new SimpleIntegerProperty(0);
        IntegerProperty rSize = new SimpleIntegerProperty(0);
        IntegerProperty skSize = new SimpleIntegerProperty(0);
        IntegerProperty strSize = new SimpleIntegerProperty(0);

        changedObjectSizes.addAll(bSize, cSize, ciSize, gstSize, iSize, pSize, rSize, skSize, strSize);

        model.getChangedBenches().addListener((MapChangeListener.Change<? extends String, ? extends Bench> c) -> bSize.setValue(model.getChangedBenches().size()));
        model.getChangedCollections().addListener((MapChangeListener.Change<? extends String, ? extends Collection> c) -> cSize.setValue(model.getChangedCollections().size()));
        model.getChangedCollectionIndices().addListener((MapChangeListener.Change<? extends String, ? extends CollectionIndex> c) -> ciSize.setValue(model.getChangedCollectionIndices().size()));
        model.getChangedGearStyleTypes().addListener((MapChangeListener.Change<? extends String, ? extends GearStyleType> c) -> gstSize.setValue(model.getChangedGearStyleTypes().size()));
        model.getChangedItems().addListener((MapChangeListener.Change<? extends String, ? extends Item> c) -> iSize.setValue(model.getChangedItems().size()));
        model.getChangedPlaceables().addListener((MapChangeListener.Change<? extends String, ? extends Placeable> c) -> pSize.setValue(model.getChangedPlaceables().size()));
        model.getChangedRecipes().addListener((MapChangeListener.Change<? extends String, ? extends Recipe> c) -> rSize.setValue(model.getChangedRecipes().size()));
        model.getChangedSkins().addListener((MapChangeListener.Change<? extends String, ? extends Skin> c) -> skSize.setValue(model.getChangedSkins().size()));
        model.getChangedStrings().addListener((MapChangeListener.Change<? extends String, ? extends String> c) -> strSize.setValue(model.getChangedStrings().size()));
    }

    // LOADING DATA & GENERAL METHODS

    /**
     * Can return null.
     */
    String loadFile(Stage stage, String fileType, String fileExtension, String promptText) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(promptText);
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(fileType, fileExtension));
        File selected = fileChooser.showOpenDialog(stage);
        return selected == null ? null : selected.getAbsolutePath();
    }

    /**
     * Can return null.
     */
    String loadDirectory(Stage stage, String promptText) {
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle(promptText);
        File selected = dirChooser.showDialog(stage);
        return selected == null ? null : selected.getAbsolutePath();
    }

    void dumpLogs(Stage stage) {
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Select the location to save the logs at.");
        File selected = dirChooser.showDialog(stage);
        if (selected != null) {
            String path = selected.getAbsolutePath();
            LocalDateTime currTime = LocalDateTime.now();
            String fileName = "log-" + currTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss-SSS")) + ".txt";
            try {
                Path file = Paths.get(path + "\\" + fileName);
                Files.write(file, logs, StandardCharsets.UTF_8);
                logs.clear();
                print("Dumped log at " + currTime.toLocalTime());
            } catch (IOException e) {
                print("Log dump failed due to an IOException; stack trace below:");
                List<String> messagesToLog = new ArrayList<>();
                Arrays.asList(e.getStackTrace()).forEach(error -> messagesToLog.add(timestampMessage(error.toString())));
                printList(messagesToLog);
            }
        }
    }

    void enableActionButtons(List<Button> buttons) {
        buttons.forEach(item -> item.setDisable(false));
    }

    void disableActionButtons(List<Button> buttons) {
        buttons.forEach(item -> item.setDisable(true));
    }

    void loadData(List<Button> buttonsToDisable, List<Button> buttonsToEnable, String entitiesPath, String mapDirPath, boolean useAbsPath) {
        Task<Void> task = getLoadTask(entitiesPath, mapDirPath, useAbsPath);
        new Thread(() -> {
            task.run();
            Platform.runLater(() -> {
                disableActionButtons(buttonsToDisable);
                enableActionButtons(buttonsToEnable);
            });
        }).start();
    }

    Task<Void> getLoadTask(String entitiesPath, String mapDirPath, boolean useAbsPath) {
        return new Task<Void>() {
            @Override
            protected Void call() {
                try {
                    deserialize(entitiesPath);
                    model.createBlueprintMapping(mapDirPath);
                    if (mapDirPath != null && !mapDirPath.equals("")) logs.add(timestampMessage("Blueprint mapping supplementary files will be sourced from " + mapDirPath));
                    useRPathFlag = !useAbsPath;
                    if (useAbsPath) logs.add(timestampMessage("Non-standard folder structure selected; this makes comparison with existing and future data impossible unless they have the same absolute file paths."));
                    logs.add(timestampMessage("Initial setup complete."));
                } catch (Exception e) {
                    print("An error occurred while loading in data; stack trace below:");
                    List<String> messages = new ArrayList<>();
                    Arrays.stream(e.getStackTrace()).forEach(m -> messages.add(m.toString()));
                    printList(messages);
                }
                return null;
            }
        };
    }

    /**
     * Serializes current data in a new thread.
     * @param selected    the selected file from the file selector
     * @param changedOnly if true, export changed data only; else exports everything stored
     * @param selection   an int array of length 9, indicating which categories are to be exported: 0 = not exported, exported otherwise; index-category mapping as follows:
     *                    0: benches
     *                    1: collections
     *                    2: collection indices
     *                    3: gear styles
     *                    4: items
     *                    5: placeables
     *                    6: recipes
     *                    7: skins
     *                    8: strings
     */
    void serialize(File selected, boolean changedOnly, boolean usePrettyPrint, int[] selection) {
        Serializer s = new Serializer();
        Gson serializer = s.getSerializer(usePrettyPrint);
        if (selected != null) {
            if (selection.length != 9) {
                print("Invalid selection length; probably a programming oversight. No exporting was done.");
                return;
            }
            Task<Void> task = new Task<Void>() {
                @Override
                protected Void call() {
                    String dirPath = selected.getAbsolutePath();
                    LocalDateTime currTime = LocalDateTime.now();
                    String fileName = "trove-entities-" + (useRPathFlag ? "" : "ns-") + (changedOnly ? "changes " : "all ") + currTime.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS")) + ".json";
                    try {
                        String path = dirPath + "\\" + fileName;
                        JsonWriter writer = serializer.newJsonWriter(new BufferedWriter(new FileWriter(path)));
                        writer.beginObject();
                        if (selection[0] != 0)
                            s.writeBenches(writer, serializer, changedOnly ? model.getChangedBenches() : model.getSessionBenches());
                        if (selection[1] != 0)
                            s.writeCollections(writer, serializer, changedOnly ? model.getChangedCollections() : model.getSessionCollections());
                        if (selection[2] != 0)
                            s.writeCollectionIndices(writer, serializer, changedOnly ? model.getChangedCollectionIndices() : model.getSessionCollectionIndices());
                        if (selection[3] != 0)
                            s.writeGearStyles(writer, serializer, changedOnly ? model.getChangedGearStyleTypes() : model.getSessionGearStyleTypes());
                        if (selection[4] != 0)
                            s.writeItems(writer, serializer, changedOnly ? model.getChangedItems() : model.getSessionItems());
                        if (selection[5] != 0)
                            s.writePlaceables(writer, serializer, changedOnly ? model.getChangedPlaceables() : model.getSessionPlaceables());
                        if (selection[6] != 0)
                            s.writeRecipes(writer, serializer, changedOnly ? model.getChangedRecipes() : model.getSessionRecipes());
                        if (selection[7] != 0)
                            s.writeSkins(writer, serializer, changedOnly ? model.getChangedSkins() : model.getSessionSkins());
                        if (selection[8] != 0)
                            s.writeStrings(writer, serializer, changedOnly ? new Strings("en", model.getChangedStrings()) : model.getSessionStrings());
                        writer.endObject();
                        writer.close();
                        hasExportedFlag = true;
                        print("Data exported to " + path);
                    } catch (Exception e) {
                        print("Export failed due to an exception; stack trace below:");
                        List<String> errorList = Arrays.stream(e.getStackTrace()).map(StackTraceElement::toString).collect(Collectors.toList());
                        printList(errorList);
                    }
                    return null;
                }
            };
            new Thread(task).start();
        }
    }

    void deserialize(String path) {
        if (path == null || !new File(path).exists()) return;
        Serializer s = new Serializer();
        Gson serializer = s.getSerializer(false);
        try {
            JsonElement tree = JsonParser.parseReader(new FileReader(path));
            tree.getAsJsonObject().entrySet().forEach(e -> {
                String type = e.getKey();
                e.getValue().getAsJsonObject().entrySet().forEach(v -> {
                    switch (type) {
                        case "benches":
                            model.addArticleToSession(serializer.fromJson(v.getValue(), Bench.class), Enums.ObjectType.BENCH);
                            break;
                        case "collections":
                            model.addArticleToSession(serializer.fromJson(v.getValue(), Collection.class), Enums.ObjectType.COLLECTION);
                            break;
                        case "collection_indices":
                            model.addArticleToSession(serializer.fromJson(v.getValue(), CollectionIndex.class), Enums.ObjectType.COLL_INDEX);
                            break;
                        case "gear_styles":
                            model.addArticleToSession(serializer.fromJson(v.getValue(), GearStyleType.class), Enums.ObjectType.GEAR_STYLE);
                            break;
                        case "items":
                            model.addArticleToSession(serializer.fromJson(v.getValue(), Item.class), Enums.ObjectType.ITEM);
                            break;
                        case "placeables":
                            model.addArticleToSession(serializer.fromJson(v.getValue(), Placeable.class), Enums.ObjectType.PLACEABLE);
                            break;
                        case "recipes":
                            model.addArticleToSession(serializer.fromJson(v.getValue(), Recipe.class), Enums.ObjectType.RECIPE);
                            break;
                        case "skins":
                            model.addArticleToSession(serializer.fromJson(v.getValue(), Skin.class), Enums.ObjectType.SKIN);
                            break;
                        case "strings":
                            JsonObject obj = new JsonObject();
                            obj.add(type, v.getValue());
                            model.setSessionString(serializer.fromJson(obj, Strings.class));
                            break;
                        default:
                            throw new IllegalArgumentException("No such type: " + type);
                    }
                });
            });
            print("Existing data loaded from " + path);
        } catch (IOException | IllegalArgumentException e) {
            print("Import failed due to an IOException; stack trace below:");
            List<String> errorList = Arrays.stream(e.getStackTrace()).map(StackTraceElement::toString).collect(Collectors.toList());
            printList(errorList);
        }
    }

    void setupLogger(ListView<String> logger) {
        ObservableList<String> loggerItems = FXCollections.observableArrayList(logs);
        logs.addListener((ListChangeListener<String>) c -> {
            if (c.next()) {
                if (c.wasAdded()) {
                    Platform.runLater(() -> loggerItems.addAll(c.getAddedSubList()));
                }
                else if (c.wasRemoved()) {
                    Platform.runLater(() -> loggerItems.removeAll(c.getRemoved()));
                }
            }
        });
        logger.setItems(loggerItems);
    }

    boolean safeToClose() {
        return model.hasNoUnmergedChanges() && (model.hasNoMergedPaths() || hasExportedFlag);
    }

    // PARSING

    CheckBoxTreeItem<String> getParseTree(String path, Enums.ObjectType type) {
        File dir = new File(path);   // since presenter checks that the input path is a directory, we can assume that here
        List<String> paths = Arrays.stream(Objects.requireNonNull(dir.listFiles()))
                .map(File::getPath).filter(value -> value.contains(filter)).collect(Collectors.toList());

        CheckBoxTreeItem<String> rootItem = new CheckBoxTreeItem<>(path);
        rootItem.setExpanded(false);
        List<CheckBoxTreeItem<String>> nonDirectories = new ArrayList<>();
        for (String subPath : paths) {

            // if directory, recursively call the method and add them all to root; note if the sub-directory has no valid files, it gets omitted
            if (new File(subPath).isDirectory()) {
                CheckBoxTreeItem<String> subDir = getParseTree(subPath, type);

                if (!subDir.isLeaf()) {
                    rootItem.getChildren().add(getParseTree(subPath, type));
                }
            }

            // else, add path to list if it has the filter keyword
            else {
                // if npcCheck passes and contains filter word, we process the item
                boolean npcCheck = !type.equals(Enums.ObjectType.COLLECTION) || !subPath.contains("_npc");
                if (subPath.contains(filter) && npcCheck) {
                    CheckBoxTreeItem<String> item = new CheckBoxTreeItem<>(subPath);

                    // add items to selectedPaths to be parsed later
                    item.selectedProperty().addListener(event -> {
                        if (item.isSelected()) {
                            selectedPathsToParse.add(item.getValue());
                        } else {
                            selectedPathsToParse.remove(item.getValue());
                        }

                    });
                    nonDirectories.add(item);
                }
            }
        }

        // add all the non-directories back in; this is done instead of adding the items in at processing time to show the directories first
        rootItem.getChildren().addAll(nonDirectories);
        return rootItem;
    }

    void updateParseTree(Enums.ObjectType type, TreeView<String> tree, TextField directory) {
        Task<Void> task = new Task<Void>() {
            @Override
            protected Void call() {
                CheckBoxTreeItem<String> treeRoot = getParseTree(directory.getText(), type);
                Platform.runLater(() -> tree.setRoot(treeRoot));
                return null;
            }
        };
        new Thread(task).start();
    }

    void setParseDirectory(Stage stage, TextField directory, TreeView<String> tree, Enums.ObjectType type) {
        String selected = loadDirectory(stage, "Select the location to extract data from.");
        if (selected != null) {
            directory.setText(selected);
            updateParseTree(type, tree, directory);
        }
    }

    void updateParseDirectory(TextField filterField, TextField directory, TreeView<String> tree, Enums.ObjectType type) {
        filter = filterField.getText();
        if (directory.getText() != null) {
            updateParseTree(type, tree, directory);
        }
    }

    void setParseTypes(ComboBox<String> typeSelect) {
        ObservableList<String> types = FXCollections.observableArrayList("Benches", "Collections", "Collection indices", "Gear styles", "Items", "Placeables", "Professions", "Recipes", "Skins", "Strings");
        typeSelect.setItems(types);
        typeSelect.getSelectionModel().selectFirst();
    }

    public void parse(ProgressBar progressBar, Text progressText, String typeString) {
        Enums.ObjectType type = Enums.ObjectType.getType(typeString);
        Task<Void> task = getParseTask(type);
        progressBar.progressProperty().bind(task.progressProperty());
        progressText.textProperty().bind(task.messageProperty());

        new Thread(() -> {
            task.run();
            Platform.runLater(() -> {
                selectedPathsToParse.clear();
                if (!failedParsePaths.isEmpty()) {
                    failedParsePaths.clear();
                }
            });
        }).start();
    }

    public Task<Void> getParseTask(Enums.ObjectType type) {
        return new Task<Void>() {
            @Override
            protected Void call() {
                // clear out old failed paths
                failedParsePaths.clear();

                // begin parsing
                List<String> parseLogs = new ArrayList<>();
                int selectedPathsLength = selectedPathsToParse.size();
                print("Parsing " + selectedPathsLength + " objects using parser for " + type.toString().toLowerCase() + ".");
                for (int i = 0; i < selectedPathsLength; i++) {
                    updateMessage("Parsing " + type + ": " + (i + 1) + "/" + selectedPathsLength);
                    updateProgress(i, selectedPathsLength);
                    try {
                        model.createObject(selectedPathsToParse.get(i), type, useRPathFlag);
                    } catch (IOException | ParseException e) {
                        parseLogs.add(timestampMessage("Parse failure: " + e.getMessage()));
                        failedParsePaths.add(selectedPathsToParse.get(i));
                    }
                }
                parseLogs.add(timestampMessage("Parsing completed."));
                printListPlain(parseLogs);
                updateMessage("Parsing complete.");
                updateProgress(selectedPathsLength, selectedPathsLength);

                return null;
            }
        };
    }

    String getFilterText() {
        return filter;
    }

    void clearSelectedPaths() {
        selectedPathsToParse.clear();
    }

    // LOGGING

    void print(String message) {
        logs.add(timestampMessage(message));
    }

    void printList(List<String> messages) {
        List<String> messagesToLog = new ArrayList<>();
        messages.forEach(m -> messagesToLog.add(timestampMessage(m)));
        logs.addAll(messagesToLog);
    }

    void printListPlain(List<String> messages) {
        logs.addAll(messages);
    }

    String timestampMessage(String message) {
        return "[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "] " + message;
    }

    // REVIEWING

    void rScreenBindCountTexts(List<Text> texts, String[] types) {
        if (texts.size() != changedObjectSizes.size() || texts.size() != types.length) {
            if (texts.size() > 0) texts.get(0).setText("Invalid length in Text list.");
            return;
        }
        for (int i = 0; i < texts.size(); i++) {
            int currIndex = i;
            texts.get(i).textProperty().bind(Bindings.createStringBinding(() -> types[currIndex] + ": " + changedObjectSizes.get(currIndex).get(), changedObjectSizes.get(i)));
        }
    }

    void rScreenSetupDataViews(String[] types, ComboBox<String> cb, ListView<String> lv) {
        selectedPathsToMerge.clear();
        cb.setItems(FXCollections.observableArrayList(types));
        cb.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            selectedPathsToMerge.clear();
            lv.setItems(getChangedObjectPaths(newValue).sorted());
        });

        lv.setCellFactory(CheckBoxListCell.forListView(item -> {
            BooleanProperty observable = new SimpleBooleanProperty();
            observable.addListener((obs, wasSelected, isNowSelected) -> {
                if (isNowSelected) selectedPathsToMerge.add(item);
                else selectedPathsToMerge.remove(item);
            });
            return observable;
        }));
    }

    void rScreenSetupActionButtons(Button mergeButton, Button undoButton, ComboBox<String> typeText, ListView<String> lv) {
        mergeButton.setOnAction(e -> {
            if (typeText.getValue() == null || typeText.getValue().equals("") || selectedPathsToMerge.size() == 0)
                return;
            Enums.ObjectType type = Enums.ObjectType.getType(typeText.getValue());
            int pathCount = selectedPathsToMerge.size();
            Task<Void> task = new Task<Void>() {
                @Override
                protected Void call() {
                    merge(selectedPathsToMerge, type);
                    return null;
                }
            };
            new Thread(() -> {
                task.run();
                selectedPathsToMerge.clear();
                Platform.runLater(() -> lv.setItems(getChangedObjectPaths(typeText.getValue()).sorted()));
                print("Merged " + pathCount + " object" + (pathCount != 1 ? "s" : "") + " to session data.");
            }).start();
        });
        undoButton.setOnAction(e -> {
            if (mementos.isEmpty()) return;
            Task<Void> task = new Task<Void>() {
                @Override
                protected Void call() {
                    unmerge();
                    return null;
                }
            };
            new Thread(() -> {
                task.run();
                selectedPathsToMerge.clear();
                Platform.runLater(() -> lv.setItems(getChangedObjectPaths(typeText.getValue()).sorted()));
                print("Reverted previous merge.");
            }).start();
        });
    }

    ObservableList<String> getChangedObjectPaths(String type) {
        switch (Enums.ObjectType.getType(type)) {
            case BENCH:
                return FXCollections.observableArrayList(model.getChangedBenches().keySet());
            case COLLECTION:
                return FXCollections.observableArrayList(model.getChangedCollections().keySet());
            case COLL_INDEX:
                return FXCollections.observableArrayList(model.getChangedCollectionIndices().keySet());
            case GEAR_STYLE:
                return FXCollections.observableArrayList(model.getChangedGearStyleTypes().keySet());
            case ITEM:
                return FXCollections.observableArrayList(model.getChangedItems().keySet());
            case PLACEABLE:
                return FXCollections.observableArrayList(model.getChangedPlaceables().keySet());
            case RECIPE:
                return FXCollections.observableArrayList(model.getChangedRecipes().keySet());
            case SKIN:
                return FXCollections.observableArrayList(model.getChangedSkins().keySet());
            case LANG_FILE:
                return FXCollections.observableArrayList(model.getChangedStrings().keySet());
            default:
                throw new IllegalArgumentException("No such type: " + type);
        }
    }

    void merge(Set<String> pathsToMerge, Enums.ObjectType type) {
        List<Article> oldArticles = new ArrayList<>();
        List<String> newPaths = new ArrayList<>();
        pathsToMerge.forEach(p -> {
            Article sessionObj = getObject(p, type, false);
            if (sessionObj != null) {
                oldArticles.add(sessionObj);
            } else {
                newPaths.add(p);
            }
            model.addArticleToSession(getObject(p, type, true), type);
            model.removeArticle(p, type, true);
            model.addMergedPath(p, type);
        });
        mementos.push(new MergeMemento(oldArticles, newPaths, type));
    }

    void unmerge() {
        MergeMemento prevAction = mementos.pop();
        Enums.ObjectType type = prevAction.getMementoType();
        List<Article> mementosToUndo = prevAction.getState();
        List<String> pathsToRemove = prevAction.getNewPaths();
        mementosToUndo.forEach(a -> {
            model.addArticleToChanges(getObject(a.getRPath(), type, false), type, true);
            model.addArticleToSession(a, type);
        });
        pathsToRemove.forEach(p -> {
            model.addArticleToChanges(getObject(p, type, false), type, true);
            model.removeArticle(p, type, false);
        });
    }

    private Article getObject(String path, Enums.ObjectType type, boolean getChanged) {
        switch (type) {
            case BENCH:
                return getChanged ? model.getChangedBenches().get(path) : model.getSessionBenches().get(path);
            case COLLECTION:
                return getChanged ? model.getChangedCollections().get(path) : model.getSessionCollections().get(path);
            case COLL_INDEX:
                return getChanged ? model.getChangedCollectionIndices().get(path) : model.getSessionCollectionIndices().get(path);
            case GEAR_STYLE:
                return getChanged ? model.getChangedGearStyleTypes().get(path) : model.getSessionGearStyleTypes().get(path);
            case ITEM:
                return getChanged ? model.getChangedItems().get(path) : model.getSessionItems().get(path);
            case PLACEABLE:
                return getChanged ? model.getChangedPlaceables().get(path) : model.getSessionPlaceables().get(path);
            case RECIPE:
                return getChanged ? model.getChangedRecipes().get(path) : model.getSessionRecipes().get(path);
            case SKIN:
                return getChanged ? model.getChangedSkins().get(path) : model.getSessionSkins().get(path);
            case LANG_FILE:
                Map<String, String> pairs = new HashMap<>();
                if (getChanged) pairs.put(path, model.getChangedStrings().get(path));
                else pairs.put(path, model.getSessionStrings().getString(path));
                return new LangFile(pairs);
            default:
                throw new IllegalArgumentException("No such type: " + type);
        }
    }

    private static class MergeMemento {
        private final Enums.ObjectType type;
        private final List<Article> state;
        private final List<String> newPaths = new ArrayList<>(); // paths that did not exist before the change

        private MergeMemento(List<Article> object, List<String> newPaths, Enums.ObjectType type) {
            this.type = type;
            this.state = object;
            this.newPaths.addAll(newPaths);
        }

        private Enums.ObjectType getMementoType() {
            return type;
        }

        private List<Article> getState() {
            return state;
        }

        private List<String> getNewPaths() {
            return newPaths;
        }
    }
}

package com.faforever.client.mod;

import com.faforever.client.fx.PlatformService;
import com.faforever.client.i18n.I18n;
import com.faforever.client.mod.ModVersion.ModType;
import com.faforever.client.notification.NotificationService;
import com.faforever.client.preferences.ForgedAlliancePrefs;
import com.faforever.client.preferences.Preferences;
import com.faforever.client.preferences.PreferencesService;
import com.faforever.client.remote.FafService;
import com.faforever.client.task.TaskService;
import com.faforever.commons.io.ByteCopier;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ModServiceTest {

  public static final String BLACK_OPS_UNLEASHED_DIRECTORY_NAME = "BlackOpsUnleashed";
  private static final ClassPathResource BLACKOPS_SUPPORT_MOD_INFO = new ClassPathResource("/mods/blackops_support_mod_info.lua");
  private static final ClassPathResource BLACKOPS_UNLEASHED_MOD_INFO = new ClassPathResource("/mods/blackops_unleashed_mod_info.lua");
  private static final ClassPathResource ECO_MANAGER_MOD_INFO = new ClassPathResource("/mods/eco_manager_mod_info.lua");
  private static final long TIMEOUT = 5000;
  private static final TimeUnit TIMEOUT_UNIT = TimeUnit.MILLISECONDS;

  @Rule
  public TemporaryFolder modsDirectory = new TemporaryFolder();
  @Rule
  public TemporaryFolder faDataDirectory = new TemporaryFolder();
  @Rule
  public TemporaryFolder corruptedModsDirectory = new TemporaryFolder();

  @Mock
  PreferencesService preferencesService;
  @Mock
  private Preferences preferences;
  @Mock
  private ForgedAlliancePrefs forgedAlliancePrefs;
  @Mock
  private ApplicationContext applicationContext;
  @Mock
  private TaskService taskService;
  @Mock
  private FafService fafService;
  @Mock
  private NotificationService notificationService;
  @Mock
  private I18n i18n;
  @Mock
  private PlatformService platformService;
  @Mock
  private ModInfoMapper modInfoMapper;

  private ModService instance;
  private Path gamePrefsPath;
  private Path blackopsSupportPath;

  @Before
  public void setUp() throws Exception {
    instance = new ModService(taskService, fafService, preferencesService, applicationContext,
      notificationService, i18n, platformService, Mappers.getMapper(ModInfoMapper.class));

    gamePrefsPath = faDataDirectory.getRoot().toPath().resolve("game.prefs");

    when(preferencesService.getPreferences()).thenReturn(preferences);
    when(preferences.getForgedAlliance()).thenReturn(forgedAlliancePrefs);
    when(forgedAlliancePrefs.getPreferencesFile()).thenReturn(gamePrefsPath);
    when(forgedAlliancePrefs.getModsDirectory()).thenReturn(modsDirectory.getRoot().toPath());
    when(forgedAlliancePrefs.modsDirectoryProperty()).thenReturn(new SimpleObjectProperty<>(modsDirectory.getRoot().toPath()));
    // FIXME how did that happen... I see this line many times but it doesn't seem to do anything useful
    doAnswer(invocation -> invocation.getArgument(0)).when(taskService).submitTask(any());

    blackopsSupportPath = copyMod(BLACK_OPS_UNLEASHED_DIRECTORY_NAME, BLACKOPS_UNLEASHED_MOD_INFO);

    instance.afterPropertiesSet();
  }

  private Path copyMod(String directoryName, ClassPathResource classPathResource) throws IOException {
    Path targetDir = modsDirectory.getRoot().toPath().resolve(directoryName);
    Files.createDirectories(targetDir);

    try (InputStream inputStream = classPathResource.getInputStream();
         OutputStream outputStream = Files.newOutputStream(targetDir.resolve("mod_info.lua"))) {
      ByteCopier.from(inputStream)
        .to(outputStream)
        .copy();
    }
    return targetDir;
  }

  @Test
  public void testPostConstructLoadInstalledMods() {
    ObservableList<ModVersion> installedModVersions = instance.getInstalledModVersions();

    assertThat(installedModVersions.size(), is(1));
  }

  @Test
  public void testLoadInstalledModsLoadsMods() throws Exception {
    assertThat(instance.getInstalledModVersions().size(), is(1));
    copyMod("BlackopsSupport", BLACKOPS_SUPPORT_MOD_INFO);
    assertThat(instance.getInstalledModVersions().size(), is(1));
    instance.loadInstalledMods();
    assertThat(instance.getInstalledModVersions().size(), is(2));
  }

  @Test
  public void testDownloadAndInstallMod() throws Exception {
    assertThat(instance.getInstalledModVersions().size(), is(1));

    InstallModTask task = stubInstallModTask();
    task.getFuture().complete(null);

    when(applicationContext.getBean(InstallModTask.class)).thenReturn(task);

    URL modUrl = new URL("http://example.com/some/mod.zip");

    copyMod("BlackopsSupport", BLACKOPS_SUPPORT_MOD_INFO);
    assertThat(instance.getInstalledModVersions().size(), is(1));

    instance.downloadAndInstallMod(modUrl).toCompletableFuture().get(TIMEOUT, TIMEOUT_UNIT);

    assertThat(instance.getInstalledModVersions().size(), is(2));
  }

  @Test
  public void testDownloadAndInstallModWithProperties() throws Exception {
    assertThat(instance.getInstalledModVersions().size(), is(1));

    InstallModTask task = stubInstallModTask();
    task.getFuture().complete(null);

    when(applicationContext.getBean(InstallModTask.class)).thenReturn(task);

    URL modUrl = new URL("http://example.com/some/mod.zip");

    copyMod("BlackopsSupport", BLACKOPS_SUPPORT_MOD_INFO);
    assertThat(instance.getInstalledModVersions().size(), is(1));

    StringProperty stringProperty = new SimpleStringProperty();
    DoubleProperty doubleProperty = new SimpleDoubleProperty();

    instance.downloadAndInstallMod(modUrl, doubleProperty, stringProperty).toCompletableFuture().get(TIMEOUT, TIMEOUT_UNIT);

    assertThat(stringProperty.isBound(), is(true));
    assertThat(doubleProperty.isBound(), is(true));

    assertThat(instance.getInstalledModVersions().size(), is(2));
  }

  @Test
  public void testDownloadAndInstallModInfoBeanWithProperties() throws Exception {
    assertThat(instance.getInstalledModVersions().size(), is(1));

    InstallModTask task = stubInstallModTask();
    task.getFuture().complete(null);

    when(applicationContext.getBean(InstallModTask.class)).thenReturn(task);

    URL modUrl = new URL("http://example.com/some/modVersion.zip");

    copyMod("BlackopsSupport", BLACKOPS_SUPPORT_MOD_INFO);
    assertThat(instance.getInstalledModVersions().size(), is(1));

    StringProperty stringProperty = new SimpleStringProperty();
    DoubleProperty doubleProperty = new SimpleDoubleProperty();

    ModVersion modVersion = ModInfoBeanBuilder.create().defaultValues().downloadUrl(modUrl).get();
    instance.downloadAndInstallMod(modVersion, doubleProperty, stringProperty).toCompletableFuture().get(TIMEOUT, TIMEOUT_UNIT);

    assertThat(stringProperty.isBound(), is(true));
    assertThat(doubleProperty.isBound(), is(true));

    assertThat(instance.getInstalledModVersions().size(), is(2));
  }

  @Test
  public void testGetInstalledModUids() throws Exception {
    copyMod("BlackopsSupport", BLACKOPS_SUPPORT_MOD_INFO);
    instance.loadInstalledMods();

    Set<UUID> installedModUids = instance.getInstalledModUids();

    assertThat(installedModUids, containsInAnyOrder(
      UUID.fromString("9e8ea941-c306-4751-b367-f00000000005"),
      UUID.fromString("9e8ea941-c306-4751-b367-a11000000502")
    ));
  }

  @Test
  public void testGetInstalledUiModsUids() throws Exception {
    assertThat(instance.getInstalledModVersions().size(), is(1));

    copyMod("EM", ECO_MANAGER_MOD_INFO);

    instance.loadInstalledMods();
    assertThat(instance.getInstalledModVersions().size(), is(2));

    Set<UUID> installedUiModsUids = instance.getInstalledUiModsUids();

    assertThat(installedUiModsUids, contains(UUID.fromString("b2cde810-15d0-4bfa-af66-ec2d6ecd561b")));
  }

  @Test
  public void testEnableSimModsClean() throws Exception {
    Files.createFile(gamePrefsPath);

    HashSet<UUID> simMods = new HashSet<>();
    simMods.add(UUID.fromString("9e8ea941-c306-4751-b367-f00000000005"));
    simMods.add(UUID.fromString("9e8ea941-c306-4751-b367-a11000000502"));
    instance.enableSimMods(simMods);

    List<String> lines = Files.readAllLines(gamePrefsPath);

    assertThat(lines, contains(
      "active_mods = {",
      "    ['9e8ea941-c306-4751-b367-f00000000005'] = true,",
      "    ['9e8ea941-c306-4751-b367-a11000000502'] = true",
      "}"
    ));
  }

  @Test
  public void testEnableSimModsModDisableUnselectedMods() throws Exception {
    Iterable<? extends CharSequence> lines = Arrays.asList(
      "active_mods = {",
      "    ['9e8ea941-c306-4751-b367-f00000000005'] = true,",
      "    ['9e8ea941-c306-4751-b367-a11000000502'] = true",
      "}"
    );
    Files.write(gamePrefsPath, lines);

    HashSet<UUID> simMods = new HashSet<>();
    simMods.add(UUID.fromString("9e8ea941-c306-4751-b367-a11000000502"));
    instance.enableSimMods(simMods);

    lines = Files.readAllLines(gamePrefsPath);

    assertThat(lines, contains(
      "active_mods = {",
      "    ['9e8ea941-c306-4751-b367-a11000000502'] = true",
      "}"
    ));
  }

  @Test
  public void testExtractModInfo() throws Exception {
    copyMod("BlackopsSupport", BLACKOPS_SUPPORT_MOD_INFO);
    copyMod("EM", ECO_MANAGER_MOD_INFO);
    instance.loadInstalledMods();

    ArrayList<ModVersion> installedModVersions = new ArrayList<>(instance.getInstalledModVersions());
    installedModVersions.sort(Comparator.comparing(ModVersion::getDisplayName));

    ModVersion modVersion = installedModVersions.get(0);

    assertThat(modVersion.getDisplayName(), is("BlackOps Global Icon Support Mod"));
    assertThat(modVersion.getVersion(), is(new ComparableVersion("5")));
    assertThat(modVersion.getUploader(), is("Exavier Macbeth, DeadMG"));
    assertThat(modVersion.getDescription(), is("Version 5.0. This mod provides global icon support for any mod that places their icons in the proper folder structure. See Readme"));
    assertThat(modVersion.getImagePath(), nullValue());
    assertThat(modVersion.getSelectable(), is(true));
    assertThat(modVersion.getId(), is(nullValue()));
    assertThat(modVersion.getUuid(), is(UUID.fromString("9e8ea941-c306-4751-b367-f00000000005")));
    assertThat(modVersion.getModType(), equalTo(ModType.SIM));

    modVersion = installedModVersions.get(1);

    assertThat(modVersion.getDisplayName(), is("BlackOps Unleashed"));
    assertThat(modVersion.getVersion(), is(new ComparableVersion("8")));
    assertThat(modVersion.getUploader(), is("Lt_hawkeye"));
    assertThat(modVersion.getDescription(), is("Version 5.2. BlackOps Unleased Unitpack contains several new units and game changes. Have fun"));
    assertThat(modVersion.getImagePath(), is(modsDirectory.getRoot().toPath().resolve("BlackOpsUnleashed/icons/yoda_icon.bmp")));
    assertThat(modVersion.getSelectable(), is(true));
    assertThat(modVersion.getId(), is(nullValue()));
    assertThat(modVersion.getUuid(), is(UUID.fromString("9e8ea941-c306-4751-b367-a11000000502")));
    assertThat(modVersion.getModType(), equalTo(ModType.SIM));
    assertThat(modVersion.getMountInfos(), hasSize(10));
    assertThat(modVersion.getMountInfos().get(3).getFile(), is(Paths.get("effects")));
    assertThat(modVersion.getMountInfos().get(3).getMountPoint(), is("/effects"));
    assertThat(modVersion.getHookDirectories(), contains("/blackops"));

    modVersion = installedModVersions.get(2);

    assertThat(modVersion.getDisplayName(), is("EcoManager"));
    assertThat(modVersion.getVersion(), is(new ComparableVersion("3")));
    assertThat(modVersion.getUploader(), is("Crotalus"));
    assertThat(modVersion.getDescription(), is("EcoManager v3, more efficient energy throttling"));
    assertThat(modVersion.getImagePath(), nullValue());
    assertThat(modVersion.getSelectable(), is(true));
    assertThat(modVersion.getId(), is(nullValue()));
    assertThat(modVersion.getUuid(), is(UUID.fromString("b2cde810-15d0-4bfa-af66-ec2d6ecd561b")));
    assertThat(modVersion.getModType(), equalTo(ModType.UI));
  }

  @Test
  public void testLoadInstalledModWithoutModInfo() throws Exception {
    Files.createDirectories(modsDirectory.getRoot().toPath().resolve("foobar"));

    assertThat(instance.getInstalledModVersions().size(), is(1));

    instance.loadInstalledMods();

    assertThat(instance.getInstalledModVersions().size(), is(1));
  }

  @Test
  public void testIsModInstalled() {
    assertThat(instance.isModInstalled(UUID.fromString("9e8ea941-c306-4751-b367-a11000000502")), is(true));
    assertThat(instance.isModInstalled(UUID.fromString("9e8ea941-c306-4751-b367-f00000000005")), is(false));
  }

  @Test
  public void testUninstallMod() {
    UninstallModTask uninstallModTask = mock(UninstallModTask.class);
    when(applicationContext.getBean(UninstallModTask.class)).thenReturn(uninstallModTask);
    assertThat(instance.getInstalledModVersions(), hasSize(1));

    instance.uninstallMod(instance.getInstalledModVersions().get(0));

    verify(taskService).submitTask(uninstallModTask);
  }

  @Test
  public void testGetPathForMod() {
    assertThat(instance.getInstalledModVersions(), hasSize(1));

    Path actual = instance.getPathForMod(instance.getInstalledModVersions().get(0));

    Path expected = modsDirectory.getRoot().toPath().resolve(BLACK_OPS_UNLEASHED_DIRECTORY_NAME);
    assertThat(actual, is(expected));
  }

  @Test
  public void testGetPathForModUnknownModReturnsNull() {
    assertThat(instance.getInstalledModVersions(), hasSize(1));
    assertThat(instance.getPathForMod(ModInfoBeanBuilder.create().uuid(
      UUID.fromString("11111111-1111-1111-1111-111111111111")
    ).get()), Matchers.nullValue());
  }

  @Test
  public void testUploadMod() {
    ModUploadTask modUploadTask = mock(ModUploadTask.class);

    when(applicationContext.getBean(ModUploadTask.class)).thenReturn(modUploadTask);

    Path modPath = Paths.get(".");

    instance.uploadMod(modPath);

    verify(applicationContext).getBean(ModUploadTask.class);
    verify(modUploadTask).setModPath(modPath);
    verify(taskService).submitTask(modUploadTask);
  }

  private InstallModTask stubInstallModTask() {
    return new InstallModTask(preferencesService, i18n) {
      @Override
      protected Void call() {
        return null;
      }
    };
  }
}
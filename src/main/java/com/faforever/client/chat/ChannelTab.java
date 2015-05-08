package com.faforever.client.chat;

import com.faforever.client.fxml.FxmlLoader;
import com.faforever.client.player.PlayerService;
import com.faforever.client.preferences.PreferencesService;
import com.faforever.client.user.UserService;
import com.faforever.client.util.ConcurrentUtil;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableMap;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public class ChannelTab extends Tab {

  private static final String CLAN_TAG_FORMAT = "[%s] ";
  private static final ClassPathResource CHAT_HTML_RESOURCE = new ClassPathResource("/themes/default/chat_container.html");
  private static final Resource MESSAGE_ITEM_HTML_RESOURCE = new ClassPathResource("/themes/default/chat_message.html");
  private static final DateTimeFormatter SHORT_TIME_FORMAT = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT);
  private static final double ZOOM_STEP = 0.2d;
  private static final String MESSAGE_CONTAINER_ID = "chat-container";
  private static final String MESSAGE_ITEM_CLASS = "chat-message";
  private static final String CSS_STYLE_SELF = "self";

  @FXML
  WebView messagesWebView;

  @FXML
  VBox moderatorsPane;

  @FXML
  VBox friendsPane;

  @FXML
  VBox foesPane;

  @FXML
  VBox othersPane;

  @FXML
  TextField messageTextField;

  @Autowired
  UserService userService;

  @Autowired
  ChatService chatService;

  @Autowired
  FxmlLoader fxmlLoader;

  @Autowired
  ChatUserControlFactory chatUserControlFactory;

  @Autowired
  HostServices hostServices;

  @Autowired
  PreferencesService preferencesService;

  @Autowired
  PlayerService playerService;

  private final String channelName;

  private boolean isChatReady;
  private WebEngine engine;
  private List<ChatMessage> waitingMessages;
  private ObservableMap<String, ChatUserControl> loginToUserControl;
  private Map<String, String> userToCssStyle;
  private Map<String, PlayerInfoBean> playerInfoBeans;

  public ChannelTab(String channelName, boolean isPrivate) {
    this.channelName = channelName;

    userToCssStyle = new HashMap<>();
    waitingMessages = new ArrayList<>();
    loginToUserControl = FXCollections.observableHashMap();

    setClosable(true);
    setId(channelName);
    setText(channelName);
  }

  @PostConstruct
  void init() {
    playerService.getKnownPlayers().addListener((MapChangeListener<String, PlayerInfoBean>) change -> {
      onPlayerInfoAdded(change.getValueAdded());
    });
    fxmlLoader.loadCustomControl("channel_tab.fxml", this);
    initChatView();
    userToCssStyle.put(userService.getUsername(), CSS_STYLE_SELF);
  }

  @FXML
  void initialize() {
    loginToUserControl.addListener((MapChangeListener<String, ChatUserControl>) change -> {
      Collection<Pane> targetPanes = getTargetPanesForUser(change.getValueAdded().getPlayerInfoBean());

      if (change.wasAdded()) {
        for (Pane targetPane : targetPanes) {
          targetPane.getChildren().add(change.getValueAdded());
        }
      } else {
        for (Pane targetPane : targetPanes) {
          targetPane.getChildren().remove(change.getValueRemoved());
        }
      }
    });
  }

  private Collection<Pane> getTargetPanesForUser(PlayerInfoBean playerInfoBean) {
    ArrayList<Pane> panes = new ArrayList<>(2);

    if (playerInfoBean.isFriend()) {
      panes.add(friendsPane);
    } else if (playerInfoBean.isFoe()) {
      panes.add(foesPane);
    }

    if (playerInfoBean.isModerator()) {
      panes.add(moderatorsPane);
    }

    if (panes.isEmpty()) {
      panes.add(othersPane);
    }

    return panes;
  }

  private WebEngine initChatView() {
    messagesWebView.setContextMenuEnabled(false);
    messagesWebView.setOnScroll(event -> {
      if (event.isControlDown()) {
        if (event.getDeltaY() > 0) {
          messagesWebView.setZoom(messagesWebView.getZoom() + ZOOM_STEP);
        } else {
          messagesWebView.setZoom(messagesWebView.getZoom() - ZOOM_STEP);
        }
      }
    });
    messagesWebView.setOnKeyPressed(event -> {
      if (event.isControlDown() && (event.getCode() == KeyCode.DIGIT0 || event.getCode() == KeyCode.NUMPAD0)) {
        messagesWebView.setZoom(1);
      }
    });
    messagesWebView.zoomProperty().addListener((observable, oldValue, newValue) -> {
      preferencesService.getPreferences().getChat().setZoom(newValue.doubleValue());
      preferencesService.storeInBackground();
    });
    Double zoom = preferencesService.getPreferences().getChat().getZoom();
    if (zoom != null) {
      messagesWebView.setZoom(zoom);
    }


    engine = messagesWebView.getEngine();
    engine.setUserDataDirectory(preferencesService.getPreferencesDirectory().toFile());
    ((JSObject) engine.executeScript("window")).setMember("channelTab", this);
    engine.getLoadWorker().stateProperty().addListener((observable, oldValue, newValue) -> {
      if (Worker.State.SUCCEEDED.equals(newValue)) {
        waitingMessages.forEach(this::appendMessage);
        waitingMessages.clear();
        isChatReady = true;
      }
    });


    try {
      this.engine.load(CHAT_HTML_RESOURCE.getURL().toExternalForm());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return engine;
  }

  /**
   * Called from JavaScript.
   */
  public void openUrl(String url) {
    hostServices.showDocument(url);
  }

  @FXML
  private void onSendMessage(ActionEvent actionEvent) {
    String text = messageTextField.getText();
    if (StringUtils.isEmpty(text)) {
      return;
    }

    chatService.sendMessage(channelName, text);
    messageTextField.clear();
    onMessage(new ChatMessage(Instant.now(), userService.getUsername(), text));
  }

  public void onMessage(ChatMessage chatMessage) {
    if (!isChatReady) {
      waitingMessages.add(chatMessage);
    } else {
      appendMessage(chatMessage);
      removeTopmostMessages();
      scrollToBottomIfDesired();
    }
  }

  private void scrollToBottomIfDesired() {
    // TODO add the "if desired" part
    engine.executeScript("window.scrollTo(0, document.body.scrollHeight);");
  }

  private void removeTopmostMessages() {
    int maxMessageItems = preferencesService.getPreferences().getChat().getMaxItems();

    int numberOfMessages = (int) engine.executeScript("document.getElementsByClassName('" + MESSAGE_ITEM_CLASS + "').length");
    while (numberOfMessages > maxMessageItems) {
      engine.executeScript("document.getElementsByClassName('" + MESSAGE_ITEM_CLASS + "')[0].remove()");
      numberOfMessages--;
    }
  }

  private void appendMessage(ChatMessage chatMessage) {
    String timeString = SHORT_TIME_FORMAT.format(
        ZonedDateTime.ofInstant(chatMessage.getTime(), TimeZone.getDefault().toZoneId())
    );

    // Bots are not registered in playerInfoMap. TODO fix it?
    PlayerInfoBean playerInfoBean = playerInfoBeans.get(chatMessage.getLogin());

    try (InputStream inputStream = MESSAGE_ITEM_HTML_RESOURCE.getInputStream()) {
      String login = chatMessage.getLogin();
      String html = IOUtils.toString(inputStream);

      String avatar = "";
      String clanTag = "";
      if (playerInfoBean != null) {
        avatar = playerInfoBean.getAvatarUrl();

        if (StringUtils.isNotEmpty(playerInfoBean.getClan())) {
          clanTag = String.format(CLAN_TAG_FORMAT, playerInfoBean.getClan());
        }
      }

      String text = StringEscapeUtils.escapeHtml4(chatMessage.getMessage()).replace("\\", "\\\\");
      text = (String) engine.executeScript("link('" + text.replace("'", "\\'") + "')");

      html = html.replace("{time}", timeString)
          .replace("{avatar}", StringUtils.defaultString(avatar))
          .replace("{username}", login)
          .replace("{clan-tag}", clanTag)
          .replace("{text}", text);

      if (userToCssStyle.containsKey(login)) {
        html = html.replace("{css-class}", userToCssStyle.get(login));
      }

      addToMessageContainer(html);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void addToMessageContainer(String html) {
    ((JSObject) engine.executeScript("document.getElementById('" + MESSAGE_CONTAINER_ID + "')"))
        .call("insertAdjacentHTML", "beforeend", html);
  }

  public void setPlayerInfoAsync(Map<String, PlayerInfoBean> playerInfoBeans) {
    this.playerInfoBeans = playerInfoBeans;
    loginToUserControl.clear();

    ConcurrentUtil.executeInBackground(
        new Task<Void>() {
          @Override
          protected Void call() throws Exception {
            ArrayList<PlayerInfoBean> playerList = new ArrayList<>(playerInfoBeans.values());
            Collections.sort(playerList, PlayerInfoBean.SORT_BY_NAME_COMPARATOR);

            for (PlayerInfoBean playerInfoBean : playerList) {
              if (isCancelled()) {
                break;
              }

              ChatUserControl chatUserControl = chatUserControlFactory.newChatUserControl(playerInfoBean);
              Platform.runLater(() -> addChatUserControlIfNecessary(chatUserControl));
            }

            return null;
          }
        }
    );
  }

  private void addChatUserControlIfNecessary(ChatUserControl chatUserControl) {
    String username = chatUserControl.getPlayerInfoBean().getUsername();

    loginToUserControl.putIfAbsent(username, chatUserControl);
  }

  public void onPlayerInfoAdded(PlayerInfoBean playerInfoBean) {
    addChatUserControlIfNecessary(chatUserControlFactory.newChatUserControl(playerInfoBean));
  }

  public void onUserLeft(String login) {
    loginToUserControl.remove(login);
  }
}

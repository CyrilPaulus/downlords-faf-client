package com.faforever.client.replay;

import com.faforever.client.legacy.domain.ClientMessage;
import com.faforever.client.legacy.domain.ClientMessageType;

public class ListReplaysMessage extends ClientMessage {

  public ListReplaysMessage() {
    setCommand(ClientMessageType.LIST_REPLAYS);
  }
}

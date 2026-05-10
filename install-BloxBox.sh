#!/usr/bin/bash

# DEFAULTS
  LOCK_REQUEST_GAMES=False   # True / False
  LOCK_REQUEST_PIN_PASS_HASH=""
  child_USERNAME=

ID=$(id -u)
if [ "$ID" != 0 ];then
    echo " RUN:  sudo $0 ../bloxbox-java.tgz"
    exit
else
    cd "$(dirname "$0")"
fi

pwd_current=$(pwd)
current_dir_path=$(echo "${pwd_current%/*}")
current_dir=$(echo "${pwd_current##*/}")


if [ -z "$1" ];then
    echo " RUN:  sudo $0 bloxbox-java.tgz"
    exit
else
    if echo "$1"|grep -q '.tgz' ;then
        echo 'Go time' 
    else    
        exit
    fi
fi
    
    SU_USER=
    DIR=/opt/bloxbox-java
    ETC=/etc/bloxbox
    DECKTOP_ICON_FILENAME=bloxbox.desktop
    WHITELIST_FILENAME=roblox_whitelist.json
    APP_WINDOW_TITLE_NAME='BloxBox'
    

    echo "  Installing in at $DIR"
    echo '';read -p '      Press enter to continue...' THREE
    sleep 3

    tar -C /opt -xzvf $1
    sudo mkdir -p $ETC
   
    chmod 755 $DIR
    chmod 755 $ETC
    chmod 644 $DIR/*
    chmod 755 $DIR/*.sh
    chmod 644 $DIR/*.py
    chmod 600 $DIR/tar-up.sh
    chmod 700 $DIR/admin.py
    chmod 600 $DIR/install-BloxBox.sh
    chmod 755 $DIR/icon
    chmod 644 $DIR/icon/*
    chown root:root -R $DIR
    chown root:root -R $ETC


INSTALL_ETC_CONFIG() {
    if [ -z $child_USERNAME ];then
       echo '';echo "  INFO: Child's username will be installed into the configuration file. $ETC/config.py"
       read -p "    What is the child's username?  $> " child_USERNAME
   
        if [ ! -z $child_USERNAME ];then
            HOME_DIR=$(echo "/home/$child_USERNAME")
            if [ ! -d $HOME_DIR ];then
                echo "$HOME_DIR was not found..."
                exit
            fi
        else
          echo "ERROR: \$child_USERNAME not found"
          exit
        fi

        if [ -f $ETC/config.py ];then
          mv $ETC/config.py $ETC/old.config.py
          chmod 600 $ETC/old.config.py
        fi

        while true;do
          read -p "  Lock the request for games behind a password/pin?  [y/n] $> " YESSIR
          if [ "$YESSIR" == y ];then
              read -rsp "   Type your password/pin/passcode you would like to use to protect the Request Games button $> " PINPASSWORD
              COUNT=0
              COUNT=$(echo -n "$PINPASSWORD" | wc -c)
              if [ "$COUNT" -ge 2 ];then
                LOCK_REQUEST_PIN_PASS_HASH=$(echo -n "$PINPASSWORD" | sha256sum | awk '{print $1}')
                LOCK_REQUEST_GAMES=True
                break        
              else
                echo " ### ERROR: needs to be greater than 2 characters... ###"
                continue
              fi
          fi
        done

#### CONFIG FILE
echo "from pathlib import Path

# ── Paths ─────────────────────────────────────────────────────────────────────
CHILD_USER    = \"$child_USERNAME\"               # ← change this to your son's username

CONFIG_PATH   = \"/etc/bloxbox/roblox_whitelist.json\"   # Approved games list (root-owned)
APP_WINDOW_TITLE_NAME = \"$APP_WINDOW_TITLE_NAME\"

ROBLOX_GAME_SEARCH_URL = \"https://www.roblox.com/charts?device=computer&country=us\"

# Requests file — lives in the child's home directory so they can write to it freely
# Parent reads it with: sudo cat /home/CHILDNAME/.bloxbox_requests.json
REQUESTS_PATH = f\"/home/{CHILD_USER}/.cache/bloxbox_launcher/requests.json\"

### Launcher GUI
# Thumbnail cache directory — stored in child's home, safe to delete any time
CACHE_DIR     = Path.home() / \".cache\" / \"bloxbox_launcher\" / \"thumbnails\"
CLIENT_REQUESTS_PATH = Path.home() / \".cache\" / \"bloxbox_launcher\" / \"requests.json\"

GAME_CATEGORIES = ['🎭 Role Playing', '🏎️ Racing', '👨‍👩‍👧 Family', '📚 Education', '🌍 World Exploring']
GAME_UNSORTED_CATEGORY = '🔧 Unsorted'

LOCK_REQUEST_GAMES = \"$LOCK_REQUEST_GAMES\" # True / False
LOCK_REQUEST_PIN_PASS_HASH = \"$LOCK_REQUEST_PIN_PASS_HASH\"

GAME_APPROVAL_NEEDED = \"True\"     # True / False
LOCK_APPROVAL_PIN = \"True\"        # True / False
LOCK_APPROVAL_PIN_PASS_HASH = \"\"

ROBLOX_GAME_SEARCH_URL = 'https://www.roblox.com/charts?device=computer&country=us'
" > $ETC/config.py
        chmod 644 $ETC/config.py
        chown root:root -R $ETC
   fi 
        chmod 644 $ETC/config.py
        chown root:root -R $ETC
}

DEFAULT_JSON() {
  if [ -f $ETC/$WHITELIST_FILENAME ];then
    mv $ETC/$WHITELIST_FILENAME $ETC/old.$WHITELIST_FILENAME
    chmod 600 $ETC/old.$WHITELIST_FILENAME
  fi

echo '{
  "games": [
    {
      "name": "[\ud83c\udf7c] Welcome to Bloxburg \ud83c\udfe1",
      "place_id": "185655149",
      "category": "Role Playing",
      "description": "",
      "url": "https://www.roblox.com/games/185655149/Welcome-to-Bloxburg"
    },
    {
      "name": "\ud83d\udc23 Creatures of Sonaria \ud83d\udc07",
      "place_id": "5233782396",
      "category": "Role Playing",
      "description": "",
      "url": "https://www.roblox.com/games/5233782396/Creatures-of-Sonaria-Survive-Kaiju-Animals"
    },
    {
      "name": "\ud83c\udfc0Basketball Legends\ud83c\udfc0",
      "place_id": "14259168147",
      "description": "",
      "url": "https://www.roblox.com/games/14259168147/Basketball-Legends"
    },
    {
      "name": "Brookhaven \ud83c\udfe1",
      "place_id": "4924922222",
      "url": "https://www.roblox.com/games/4924922222/Brookhaven-RP",
      "category": "Role Playing",
      "description": ""
    },
    {
      "name": "Bike of Hell",
      "place_id": "14943334555",
      "category": "Racing",
      "description": ""
    },
    {
      "name": "Waterpark",
      "place_id": "76731635",
      "description": ""
    },
    {
      "name": "Car Suspension Test",
      "place_id": "6816975827",
      "category": "Racing",
      "description": ""
    },
    {
      "name": "Driving-Empire-Car-Racing",
      "place_id": "3351674303",
      "category": "Racing",
      "description": ""
    },
    {
      "name": "Feather Family",
      "place_id": "1365404657",
      "category": "Family",
      "description": ""
    },
    {
      "name": "Car Crushers 2",
      "place_id": "654732683",
      "category": "Racing",
      "description": ""
    },
    {
      "name": "Basketball: Zero",
      "place_id": "130739873848552",
      "description": ""
    },
    {
      "name": "United States Capitol [RP]",
      "place_id": "120992074793516",
      "category": "Education",
      "description": ""
    },
    {
      "name": "Math Tower \ud83e\udde0",
      "place_id": "76490888522129",
      "category": "Education",
      "description": "",
      "url": "https://www.roblox.com/games/76490888522129/Math-Tower"
    },
    {
      "name": "Infinite Math \ud83e\udde0",
      "place_id": "77972109461154",
      "category": "Education",
      "description": "",
      "url": "https://www.roblox.com/games/77972109461154/Infinite-Math"
    }
  ]
}
'       > $ETC/$WHITELIST_FILENAME
chmod 644 $ETC/$WHITELIST_FILENAME
echo "Config File placed at: $ETC/$WHITELIST_FILENAME"
ls -al $ETC/$WHITELIST_FILENAME
}


DEFAULT_DESKTOP_ICON() {
echo '[Desktop Entry]
Type=Application
Name=Bloxbox Roblox Launcher
GenericName=Bloxbox Roblox Launcher
Comment=Play, chat & explore more safely on Roblox
Icon=/opt/bloxbox-java/icon/bloxbox-icon.svg
Exec=/opt/bloxbox-java/run_bloxbox_gui.sh
Name[en_US]=Bloxbox Roblox Launcher
Keywords=roblox;vinegar;game;gaming;social;experience;launcher;
MimeType=x-scheme-handler/roblox;x-scheme-handler/roblox-player;
Categories=Game;
Terminal=false
PrefersNonDefaultGPU=true
Actions=open-settings;
X-Flatpak=org.vinegarhq.Sober
'       > /usr/share/applications/$DECKTOP_ICON_FILENAME
chmod 755 /usr/share/applications/$DECKTOP_ICON_FILENAME
update-desktop-database
echo "Desktop Icon placed at: /usr/share/applications/$DECKTOP_ICON_FILENAME"
ls -al /usr/share/applications/$DECKTOP_ICON_FILENAME

echo '[Desktop Entry]
Type=Application
Name=Bloxbox Roblox Launcher
GenericName=Bloxbox Roblox Launcher
Comment=Play, chat & explore more safely on Roblox
Icon=/opt/bloxbox-java/icon/bloxbox-icon.svg
Exec=/opt/bloxbox-java/run_bloxbox_gui.sh
Name[en_US]=Bloxbox Roblox Launcher
Keywords=roblox;vinegar;game;gaming;social;experience;launcher;
MimeType=x-scheme-handler/roblox;x-scheme-handler/roblox-player;
Categories=Game;
Terminal=false
PrefersNonDefaultGPU=true
Actions=open-settings;
X-Flatpak=org.vinegarhq.Sober
'       > $HOME_DIR/Desktop/$DECKTOP_ICON_FILENAME
chmod 755 $HOME_DIR/Desktop/$DECKTOP_ICON_FILENAME
#chown root:root $HOME_DIR/Desktop/$DECKTOP_ICON_FILENAME
chown $child_USERNAME:$child_USERNAME $HOME_DIR/Desktop/$DECKTOP_ICON_FILENAME
echo "Desktop Icon placed at: $HOME_DIR/Desktop/$DECKTOP_ICON_FILENAME"
ls -al $HOME_DIR/Desktop/$DECKTOP_ICON_FILENAME
}



if [ ! -f $ETC/config.py ];then
  INSTALL_ETC_CONFIG
else
    echo "";echo "  INFO: this will backup the current file at $ETC/old.config.py"
    read -p "     Install [Default/New/Fresh]  /etc/config.py?   [y] $> " SAYnoMore

    if [ "$SAYnoMore" == y ];then
        INSTALL_ETC_CONFIG
    fi
fi


if [ ! -f $ETC/$WHITELIST_FILENAME ];then
    sudo python3 $DIR/admin.py init
    DEFAULT_JSON
else
    echo "";echo "  INFO: this will backup the current file at $ETC/old.$WHITELIST_FILENAME"
    read -p "     Install Default Whitelist Config of Games?   [y] $> " SAY

    if [ "$SAY" == y ];then
        DEFAULT_JSON
    fi
fi


if [ -z $child_USERNAME ];then
       echo '';read -p "    What is the child's username?  $> " child_USERNAME
   
        if [ ! -z $child_USERNAME ];then
            HOME_DIR=$(echo "/home/$child_USERNAME")
            if [ ! -d $HOME_DIR ];then
                echo "$HOME_DIR was not found..."
                exit
            fi
        else
            echo "ERROR: \$child_USERNAME not found"
            exit
        fi
fi

if [ ! -z $child_USERNAME ];then
  if [ ! -f $HOME_DIR/Desktop/$DECKTOP_ICON_FILENAME ];then
      DEFAULT_DESKTOP_ICON
  else
      echo "";echo "  WARNING: this will overwrite the current file at $HOME_DIR/Desktop/$DECKTOP_ICON_FILENAME"
      read -p "     Install Default Desktop Icon?   [y] $> " SAYyou

      if [ "$SAYyou" == y ];then
          DEFAULT_DESKTOP_ICON
      fi
  fi
fi
#sudo apt install flatpak

##### Not sure which is needed
    #sudo sysctl -w kernel.unprivileged_userns_clone=1
    #echo 'kernel.unprivileged_userns_clone=1' | sudo tee /etc/sysctl.d/99-userns.conf
    #sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0
    #echo 'kernel.apparmor_restrict_unprivileged_userns=0' | sudo tee /etc/sysctl.d/99-userns.conf




#su $SU_USER -c 'flatpak install flathub org.vinegarhq.Sober'


exit

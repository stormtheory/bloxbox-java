<div align="center"><img width="280" height="280" alt="Image" src="https://github.com/user-attachments/assets/da74118b-c19f-461c-a132-de6bd6ee7719" /></div>
<h1 align="center">BloxBox</h1>
<h3 align="center">A safer way to have your kids play. Since April 2026</h3>

<h4 align="center">Keeping secrets safe. Since April 2026</h4>

## Overview

The Roblox launcher that puts parents in control for PC using java. Only showing approved games. The parent-controlled Roblox launcher whitelists approved games, block everything else, and let kids request new ones.

Please submit all problems/issues/sugeestions to https://github.com/stormtheory/bloxbox-java/issues

<img width="971" height="737" alt="Image" src="https://github.com/user-attachments/assets/4e95a691-9ff7-4231-a9d3-d985b2cf973f" />

---

## 🖥️ Features and Design

---

## 🖥️ Platforms Supported (Tested On)

    ✅ Debian 11+
    ✅ Ubuntu 20.04/22.04+
    ✅ Linux Mint 20+
    ✅ Windows 7/10/11

## ⚙️ Requirements

* Java JDK 17+ (tested on newer versions)

No external database or installer required.

---

## 🚀 Future Improvements

**[ New Features ]**


**[ Big Ticket Items ]**


**[ New Data Storage ]**


---

## INSTALL:
1) Download the latest released .jar package files off of github at https://github.com/stormtheory/bloxbox-java/releases and install on your system.

          #### Windows/Linux/MacOS ####
          # Download then execute like normal or use Linux command:

          java -jar BloxBox-Java-*.jar


2) Manual Install without Package Manager, run commands:

    Download the zip file of the code, off of Github. This is found under the [<> Code] button on https://github.com/stormtheory/bloxbox-java.

    Extract directory from the zip file. Run the following commands within the directory.

        #/In Folder Requirements
          Backend.java
          GUI.java
          IdleTimeoutManager.java
          lib/sqlite-jdbc-3.53.0.0.jar
          lib/argon2-jvm-2.12.jar
          lib/bcprov-jdk18on-1.84.jar
          bin/
          icons/


        # Linux Install or edit code:
            cd java-password-vault
                ./build.sh -br  # Build and Run

                # or

                ./build.sh -r  # Run
            

        # Windows Install or edit code:
                .\run.bat -br # Build and Run

                # or

                .\run.bat           
              

## RUN:
### run the local App

        # Linux:
            cd bloxbox-java
            ./build.sh -r

        # Windows:
            Within the folder run command:
            .\run.bat

## Game Manage / Approvals / Requests
        ## Use the following command and arguments:
                sudo /opt/bloxbox-launcher/admin.py init            — first-time setup
                sudo /opt/bloxbox-launcher/admin.py list            — show approved games
                sudo /opt/bloxbox-launcher/admin.py add             — approve a new game
                sudo /opt/bloxbox-launcher/admin.py remove          — remove an approved game
                sudo /opt/bloxbox-launcher/admin.py requests        — view pending requests from child
                sudo /opt/bloxbox-launcher/admin.py clear-requests  — clear all reviewed requests

## Create .jar file, run commands:
  ✔ Works on all platforms
  ✔ No classpath needed
  ✔ No extra files

  Download the zip file of the code, off of Github. This is found under the `[<> Code]` button on `https://github.com/stormtheory/bloxbox-java`.

  Extract directory from the zip file. Run the following commands within the directory.

  On windows run the `.\run.bat -j` and for Linux run `./build.sh -j`


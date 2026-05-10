#!/usr/bin/env bash
cd "$(dirname "$0")"
####################################################################
##
##                   LINUX / macOS
##        Script is to make building, launching,
##  and running easier with command line (CLI) arguments
##
##               With Love, Stormtheory
##
####################################################################

JAR_FILENAME=BloxBox-Java.jar
DIR_NAME=bloxbox-java

# ── JavaFX platform detection ─────────────────────────────────────────
# Detects the current OS and CPU arch, then points FX_PATH at the
# matching JavaFX SDK lib/ folder inside ./lib/.
# Download SDKs from: https://gluonhq.com/products/javafx/
#   ./lib/linux/       ← JavaFX Linux x64 SDK lib/ contents
#   ./lib/macos-x64/   ← JavaFX macOS x64 SDK lib/ contents
#   ./lib/macos-arm/   ← JavaFX macOS aarch64 SDK lib/ contents
OS=$(uname -s)
ARCH=$(uname -m)

if [ "$OS" = "Darwin" ] && [ "$ARCH" = "arm64" ]; then
    FX_PATH="./lib/macos-arm"
elif [ "$OS" = "Darwin" ]; then
    FX_PATH="./lib/macos-x64"
else
    # Linux x64 — default for CI and most desktop installs
    FX_PATH="./lib/linux"
fi

# JavaFX modules required by BloxBox:
#   javafx.web    — WebView embedded browser (RequestDialogWebView)
#   javafx.swing  — JFXPanel bridge (embeds JavaFX scene in Swing JDialog)
#   jdk.jsobject  — JavaScript <-> Java bridge used by WebEngine
#                   ships as jdk.jsobject.jar inside the JavaFX SDK lib/
FX_MODULES="javafx.web,javafx.swing,jdk.jsobject"

echo "[JavaFX] Platform: $OS $ARCH -> $FX_PATH"

# ── SDK sanity check — fail fast with a clear message ────────────────
# Missing jars produce cryptic "package does not exist" compile errors
# that look like code bugs but are really just a missing/wrong SDK path.
# This catches the problem before javac even starts.
if ! ls "$FX_PATH"/*.jar > /dev/null 2>&1; then
    echo "[ERROR] No jars found in $FX_PATH"
    echo "        Download the JavaFX SDK from https://gluonhq.com/products/javafx/"
    echo "        and copy the SDK lib/ contents into $FX_PATH/"
    exit 1
fi
echo "[JavaFX] Jars found: $(ls "$FX_PATH"/*.jar | wc -l | tr -d ' ') in $FX_PATH"

# No running as root!
ID=$(id -u)
if [ "$ID" == '0' ]; then
    echo "Not safe to run as root... exiting..."
    exit 1
fi

# Help text
show_help() {
cat <<EOF
Usage: $(basename "$0") [OPTIONS]
Options:
  -c             Copy the tar to the downloads directory
  -i             Runs the build function
  -b             Runs the build function
  -r             Starts the GUI program
  -j             Create Jar file
  -d             debug
  -a             pass arguments
  -h             Show this help message
Example:
$0 -br
EOF
}

# Default values
DOWNLOADS=false

TAR_UP() {
    pwd_current=$(pwd)
    current_dir_path=$(echo "${pwd_current%/*}")
    current_dir=$(echo "${pwd_current##*/}")
    if [ "$current_dir" == "$DIR_NAME" ]; then
        tar --exclude="$DIR_NAME/.git" -czvf ../$DIR_NAME.tgz ../$DIR_NAME
    else
        echo "  Not $current_dir looking for $DIR_NAME"
        mv ../$current_dir ../$DIR_NAME
        tar --exclude="$DIR_NAME/.git" -czvf ../$DIR_NAME.tgz ../$DIR_NAME
    fi
    if [ "$DOWNLOADS" == true ]; then
        cp -v ../$DIR_NAME.tgz ~/Downloads
    fi
}

JAR() {
    # ===== Clean old build =====
    rm -f bin/* $JAR_FILENAME
    rm -rf fatjar

    # ===== Compile with JavaFX on module path =====
    BUILD

    # ===== Build fat jar staging area =====
    mkdir -p fatjar
    cp -r bin/* fatjar/

    # ===== Strip signature files — prevents JAR verification failure =====
    # Signed JARs (e.g. JavaFX) embed .SF/.RSA/.DSA files in META-INF.
    # When repackaged into a fat jar these signatures no longer match
    # and the JVM refuses to load the classes. Strip them.
    rm -f fatjar/META-INF/*.SF
    rm -f fatjar/META-INF/*.RSA
    rm -f fatjar/META-INF/*.DSA

    # ===== Write manifest — Main-Class must match the entry point class =====
    mkdir -p fatjar/META-INF
    printf 'Manifest-Version: 1.0\nMain-Class: launcher\n\n' > fatjar/META-INF/MANIFEST.MF

    # ===== Package fat jar =====
    # NOTE: JavaFX native libs (.so/.dylib/.dll) cannot be embedded in a
    # fat jar — they must remain on the filesystem alongside the jar.
    # Distribute ./lib/<platform>/ with the jar and use the run command below.
    cd fatjar && jar cfm ../$JAR_FILENAME META-INF/MANIFEST.MF . && cd ..

    echo "#### Done ####"
    echo "Run with:"
    echo "  java --module-path $FX_PATH --add-modules $FX_MODULES -jar $JAR_FILENAME"
}

BUILD() {
    rm -f ./bin/*
    # -encoding UTF-8: required on some systems where the default charset
    # (e.g. ISO-8859-1) cannot represent emoji/Unicode in source literals.
    # --module-path + --add-modules: make javafx.web, javafx.swing, and
    # jdk.jsobject available to the compiler — without these, any import of
    # javafx.scene.web.* or javafx.embed.swing.* fails with "package not found".
    echo "javac --module-path $FX_PATH --add-modules $FX_MODULES -encoding UTF-8 -d bin *.java"
    if [ "$DEBUG" != true ]; then
        javac --module-path "$FX_PATH" --add-modules "$FX_MODULES" \
              -encoding UTF-8 -d bin *.java
    else
        # -Xlint:deprecation surfaces deprecated API usage — useful during dev
        javac --module-path "$FX_PATH" --add-modules "$FX_MODULES" \
              -Xlint:deprecation -encoding UTF-8 -d bin *.java
    fi
}

RUN() {
    # --module-path + --add-modules: required at runtime too — the JavaFX
    # native libraries (.so/.dylib) are loaded from this path by the JVM.
    # Without it the JVM cannot find the WebView native bridge and throws
    # UnsatisfiedLinkError at the point RequestDialogWebView is opened.
    echo "java --module-path $FX_PATH --add-modules $FX_MODULES -cp ./bin launcher $ARGUMENTS"
    java --enable-native-access=javafx.graphics,javafx.web --module-path "$FX_PATH" --add-modules "$FX_MODULES" \
         -cp ./bin launcher $ARGUMENTS
}

DEBUG=false
HELP=true
ARGUMENTS=

# Parse options
while getopts ":a:ijdcbrh" opt; do
    case ${opt} in
        a)
            ARGUMENTS=$OPTARG
            ;;
        c)
            TAR_UP=true
            DOWNLOADS=true
            HELP=false
            ;;
        j)
            JAR
            exit
            ;;
        i)
            BUILD=true
            HELP=false
            ;;
        b)
            BUILD=true
            HELP=false
            ;;
        r)
            RUN=true
            HELP=false
            ;;
        d)
            DEBUG=true
            ;;
        h)
            show_help
            exit 0
            ;;
        \?)
            echo "Invalid option: -$OPTARG" >&2
            show_help
            exit 1
            ;;
        :)
            echo "Option -$OPTARG requires an argument." >&2
            show_help
            exit 1
            ;;
    esac
done

if [ "$BUILD" == true ]; then
    BUILD
fi

if [ "$TAR_UP" == true ]; then
    TAR_UP
fi

if [ "$RUN" == true ]; then
    RUN
fi

if [ "$HELP" == true ]; then
    show_help
    exit 1
fi

# Usage: ./deploy.sh <server> [-n]
# -n: Don't build the JAR file
# Example server: henry@34.73.39.47

no_build=false

# Parse command-line options
while getopts ":n" opt; do
    case $opt in
        n)
            # Set the flag if the --no-build option is present
            no_build=true
            ;;
    esac
done



if ["$no_build" = "true" ] ; then
    echo "Skipping build..."
else
    echo "Building"
    rm -r web/target/
    mvn clean && mvn package -pl web -DskipTests -am
fi

echo "SSH to " $1

[[ -z "$1" ]] && echo "Please provide a server" && exit 1

rsync -rvh transify-config1.yml custom_models web/target/graphhopper-web-8.0-SNAPSHOT.jar $1:
ssh $1 "curl https://download.geofabrik.de/north-america/canada/ontario-latest.osm.pbf -o ontario-latest.osm.pbf"

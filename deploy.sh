# henry@34.73.39.47

rm -r web/target/
mvn clean && mvn package -pl web -DskipTests -am

echo "SSH to " $1

[[ -z "$1" ]] && echo "Please provide a server" && exit 1

rsync -rvh transify-config1.yml custom_models web/target/graphhopper-web-8.0-SNAPSHOT.jar $1:
ssh $1 "curl https://download.geofabrik.de/north-america/canada/ontario-latest.osm.pbf -o ontario-latest.osm.pbf"

echo "Run command
java -jar graphhopper-web-8.0-SNAPSHOT.jar server transify-config1.yml
on server.
"

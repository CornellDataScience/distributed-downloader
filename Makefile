.PHONY: tracker peer client proto t p c pr all a

tracker:
	mvn -pl tracker spring-boot:run

# Optional: QUIET=true|false — suppresses verbose stdout on client/peer (passes -D / Spring args)
peer:
	mvn -pl peer spring-boot:run -Dspring-boot.run.arguments="--peer.port=$(or $(PORT),6001)$(if $(QUIET), --cds.distdownloader.quiet=$(QUIET),)"

client:
	mvn -f client/pom.xml -DskipTests compile exec:java -Dexec.mainClass=cds.distdownloader.client.Client $(if $(QUIET),-Dcds.distdownloader.quiet=$(QUIET),) -Dexec.args="$(or $(HOST),127.0.0.1) $(or $(TRACKER_PORT),50051) $(or $(MANIFEST),env/manifest.json) $(FILE)"

proto:
	mvn -pl proto -am generate-sources

t: tracker

p: peer

c: client

pr: proto

all:
	mvn -pl tracker,peer -am -DskipTests compile

a: all

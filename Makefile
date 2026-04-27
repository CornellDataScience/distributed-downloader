TRACKER_HOST ?= 127.0.0.1
TRACKER_PORT ?= 50051
PEER_PORT ?= $(or $(PORT),6001)
ADVERTISE_ADDRESS ?= 127.0.0.1
SHARE_FILE ?=
MANIFEST ?= env/manifest.json

.PHONY: tracker peer client proto t p c pr all a

tracker:
	mvn -pl proto -am -DskipTests install
	mvn -pl tracker spring-boot:run -Dspring-boot.run.arguments="--spring.grpc.server.port=$(TRACKER_PORT)"

# Optional: QUIET=true|false - suppress verbose stdout on client/peer (passes -D / Spring args).
peer:
	mvn -pl peer spring-boot:run -Dspring-boot.run.arguments="--peer.port=$(or $(PORT),6001)$(if $(QUIET), --cds.distdownloader.quiet=$(QUIET),)"

client:
	mvn -f client/pom.xml -DskipTests compile exec:java -Dexec.mainClass=cds.distdownloader.client.Client $(if $(QUIET),-Dcds.distdownloader.quiet=$(QUIET),) -Dexec.args="$(or $(HOST),127.0.0.1) $(or $(TRACKER_PORT),50051) $(or $(MANIFEST),env/manifest.json) $(FILE)"

proto:
	mvn -pl proto -am -DskipTests install

t: tracker

p: peer

c: client

pr: proto

all:
	mvn -pl tracker,peer -am -DskipTests compile

a: all

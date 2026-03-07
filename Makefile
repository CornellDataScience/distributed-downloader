.PHONY: tracker peer client proto t p c pr all a

tracker:
	mvn -pl tracker spring-boot:run

peer:
	mvn -pl peer spring-boot:run

client:
	mvn -f client/pom.xml -DskipTests compile exec:java -Dexec.mainClass=cds.distdownloader.client.Client

proto:
	mvn -pl proto -am generate-sources

t: tracker

p: peer

c: client

pr: proto

all:
	mvn -pl tracker,peer -am -DskipTests compile

a: all

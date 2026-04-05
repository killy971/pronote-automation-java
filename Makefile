# Makefile for pronote-automation-java
# Requires: ./gradlew (included), Java 21 on PATH for 'run'

JAVA21 := $(shell find ~/.gradle/jdks -name "java" -path "*/bin/java" 2>/dev/null | grep 21 | head -1)
JAVA    := $(if $(JAVA21),$(JAVA21),java)
JAR     := build/libs/pronote-automation-1.0.0.jar
CONFIG  := config.yaml

JVM_OPTS := -Djava.net.preferIPv4Stack=true
ARGS ?=

.PHONY: build test run run-debug views diff notify-preview clean help

## build: compile and package the fat JAR
build:
	./gradlew shadowJar
	@echo "JAR built: $(JAR)"

## test: run unit tests
test:
	./gradlew test

## run: run the application with ./config.yaml
run: $(JAR)
	@if [ ! -f "$(CONFIG)" ]; then \
		echo "ERROR: $(CONFIG) not found. Copy config.yaml.example and fill in your credentials."; \
		exit 1; \
	fi
	$(JAVA) -Xmx128m $(JVM_OPTS) -jar $(JAR) --config $(CONFIG)

## run-debug: run with DEBUG logging enabled
run-debug: $(JAR)
	@if [ ! -f "$(CONFIG)" ]; then \
		echo "ERROR: $(CONFIG) not found."; \
		exit 1; \
	fi
	$(JAVA) -Xmx128m -DLOG_LEVEL=DEBUG -jar $(JAR) --config $(CONFIG)

## views: regenerate HTML timetable views from the last snapshot (offline, no Pronote connection)
views: $(JAR)
	@if [ ! -f "$(CONFIG)" ]; then \
		echo "ERROR: $(CONFIG) not found."; \
		exit 1; \
	fi
	$(JAVA) -Xmx128m $(JVM_OPTS) -jar $(JAR) --config $(CONFIG) --mode views

## diff: re-run diff between the last two snapshots and re-notify if changes exist (offline)
diff: $(JAR)
	@if [ ! -f "$(CONFIG)" ]; then \
		echo "ERROR: $(CONFIG) not found."; \
		exit 1; \
	fi
	$(JAVA) -Xmx128m $(JVM_OPTS) -jar $(JAR) --config $(CONFIG) --mode diff $(ARGS)

## notify-preview: show what the next notification would look like without sending it (offline)
notify-preview: $(JAR)
	@if [ ! -f "$(CONFIG)" ]; then \
		echo "ERROR: $(CONFIG) not found."; \
		exit 1; \
	fi
	$(JAVA) -Xmx128m $(JVM_OPTS) -jar $(JAR) --config $(CONFIG) --mode diff --dry-run

## clean: remove build artifacts
clean:
	./gradlew clean
	@echo "Build artifacts removed."

## help: show available targets
help:
	@grep -E '^## ' Makefile | sed 's/## /  make /'

$(JAR): build

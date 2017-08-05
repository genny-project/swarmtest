FROM java:8u111-jre-alpine

ADD target/swarmtest-0.0.1-SNAPSHOT-fat.jar /test.jar


# Create our entrypoint
RUN echo "set -e; command=\"\$1\"; if [ \"\$command\" != \"java\" ]; then echo \"ERROR: command must start with: java\"; exit 1; fi; exec \"\$@\"" > /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 5701
EXPOSE 8081

ENTRYPOINT ["sh","/entrypoint.sh"]
CMD ["java"]

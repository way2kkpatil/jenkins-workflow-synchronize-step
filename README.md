# jenkins-workflow-synchronize-step
Jenkins plugin for sycnhronizing between builds

# Parameters

* key : String to represent a resource.
* timeout : duration for timeout, 0 means indefinite duration.
* unit : unit for duration. (Possible values : DAYS, HOURS, MICROSECONDS, MILLISECONDS, MINUTES, NANOSECONDS, SECONDS )

# Sample Usage

* Using variable

```java
synchronize(key: "$VARIABLE", timeout: 100, unit: SENCONDS) {
   // other code.
}
```

* Using String

```java
synchronize(key: 'some-string-id', timeout: 100, unit: SENCONDS) {
   // other code.
}
```

All jenkins job that need to synchronize on string must include synchronized code blocks.

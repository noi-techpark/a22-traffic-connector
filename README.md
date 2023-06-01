<!--
SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>

SPDX-License-Identifier: CC0-1.0
-->

# A22TrafficConnector

Retrieve A22 traffic data and store it into a PostgreSQL database.

[![ci A22TrafficConnector](https://github.com/noi-techpark/A22TrafficConnector/actions/workflows/ci.yml/badge.svg)](https://github.com/noi-techpark/A22TrafficConnector/actions/workflows/ci.yml)

## Purpose

The A22TrafficConnector is a standalone Java application. Its purpose is to retrieve
events registered by the traffic sensors along the A22 toll way and store them into
a PostgreSQL database.

## Requirements
- A JDK (at least version 8).
- Postgresql database initialized with the a22db_* scripts
- URL and credentials to access the A22 web service. This must be stored in the table `a22.a22_webservice` at id = 1. This is not needed if you point to an existing remote database

- If you use docker, you only need the webservice credentials

## Building
to build locally via maven:

```
mvn package
```

## Running

### Standalone

The application can be called as a JAR:

```
java -jar target/app.jar <arguments>
```

An environment variable `JDBC_URL` must be set to configure the DB connection.  

```
JDBC_URL=jdbc:postgresql://host/dbname?user=******&password=*****
```

`<argument>` specifies the operation mode and parameters:

```
{ month <year> <month> | interval <start_ts> <end_ts> | follow }
```

The values for `start_ts` and `end_ts` are unix timestamps with a resolution
of seconds.
 
One can use the `date` command to translates timestamps:

```
$ date -d @1554076800
Mon Apr  1 00:00:00 UTC 2019

$ date -d @1556668800
Wed May  1 00:00:00 UTC 2019
 
$ date -d "Mon Apr  1 00:00:00 UTC 2019" +%s
1554076800

$ date -d "Wed May  1 00:00:00 UTC 2019" +%s
1556668800
```

### Run using Docker compose
Create a local `.env` by copying `.env.example` and modify the configuration to your needs. 
```
$ docker-compose up
```

This starts a local Postgresql instance and initializes the schema.  
If you leave the default JDBC connection string in `.env`, the data collector runs using this local postgres DB  

If you change the webservice credentials or want to reset the data, delete the `db/` folder

If you run using an existing DB, use
```
$ docker-compose up app
```
instead. This avoids spinning up the local DB

The default mode is `follow`.  
To run in `bulk` mode supply the necessary arguments in the `.env` file

### Running in bulk mode

When the first argument is `month` or `interval`, the application will retrieve
data for the given period in ***bulk mode***.

When `month` is given, the timestamps are computed automatically.
When  `interval` is given relevant timestamps are:

```
start_ts <= timestamp < end_ts
```

In bulk mode the application will:

- retrieve the list of sensors
- download traffic events for all sensors obtained in the previous step
- store the traffic events in the table `a22.a22_traffic`
- exit

Note that in bulk mode, the application will **not** insert into or update table
`a22.a22_station`. Bulk mode is only meant to quickly do an initial load of 
table `a22.a22_traffic`.

Bulk mode is multi threaded (8 threads) and memory efficient. It can retrieve
a month of traffic data in about 8 hours with almost no load on the machine running
the application and minimal load on Postgres.

***Bulk mode is not transactionally safe***, in the sense that if it is interrupted
for whatever reason, the database will be left with partially data. It is therefore
important to check the log output after a run finishes. Here is an example log after
sucessfully loading data for april 2019:

```
A22TrafficConnector invoked at 2019-06-22T22:52:38.427Z[UTC]
args: month 2019 4
start: 1554076800
end:   1556668800
auth OK, new token = xxxxxxxx-xxxx-xxxx-xxxx-************, time = 2019-06-22T22:52:39.063Z[UTC]
th0 thread started at 2019-06-22T22:52:39.064Z[UTC]
th1 thread started at 2019-06-22T22:52:39.064Z[UTC]
th2 thread started at 2019-06-22T22:52:39.065Z[UTC]
th4 thread started at 2019-06-22T22:52:39.066Z[UTC]
th5 thread started at 2019-06-22T22:52:39.067Z[UTC]
th6 thread started at 2019-06-22T22:52:39.068Z[UTC]
th3 thread started at 2019-06-22T22:52:39.068Z[UTC]
th7 thread started at 2019-06-22T22:52:39.068Z[UTC]
[...]
th0 thread ended at 2019-06-23T04:36:20.303Z[UTC]
th1 thread ended at 2019-06-23T04:45:28.029Z[UTC]
th2 thread ended at 2019-06-23T04:56:12.369Z[UTC]
th3 thread ended at 2019-06-23T05:03:18.742Z[UTC]
th7 thread ended at 2019-06-23T05:15:12.189Z[UTC]
th6 thread ended at 2019-06-23T05:25:37.118Z[UTC]
th5 thread ended at 2019-06-23T05:33:04.100Z[UTC]
th4 thread ended at 2019-06-23T05:45:28.810Z[UTC]
de-auth OK, old token = xxxxxxxx-xxxx-xxxx-xxxx-************, time = 2019-06-23T05:45:28.902Z[UTC]
A22TrafficConnector exited at 2019-06-23T05:45:28.902Z[UTC]
```

If, for example, the authentication token is invalidated during operation, the
threads will log HTTP 401 responses with 0 records saved and fail de de-authentication
step. The log would end with:

```
[...]
th4 response codes: {401=xx}
th4 0 records (retrieve 3819 ms, store 0 ms)
th4 thread ended at 2019-06-05T14:10:58.043Z[UTC]
Exception in thread "main" java.lang.RuntimeException: de-authentication failure (response code was 401)
    at traffic_a22.Connector.close(Connector.java:115)
    at traffic_a22.Main.main(Main.java:152)
```

In such an event, any partial data must be deleted from the database manually:

```
delete from a22.a22_traffic where timestamp >= 1554076800 and timestamp < 1556668800;
```

and the bulk run must be repeated.

The A22 web service has two known quirks:

- The service will somewhat unfortunately return HTTP 500 when trying to retrieve
  traffic data for sensors that have no data in the requested interval. This is not
  a problem.
  
- (:exclamation:) Whenever a token is de-authenticated all other tokens of the same user are also
  de-authenticated.
  This means ***you can never have more than one instance of the application running at the same time*** as
  a de-authentication request by one run will de-authenticate the other instances as
  well. 
  
 
### Running in follow mode
 
Follow mode is the normal mode used in production after the initial load has been done.
 
Just specify `follow` as the argument.
 
In follow mode, the application will:
 
- retrieve the list of sensors and insert all sensors with yet unknown code into 
  table `a22.a22_station`
- download traffic events for all sensors obtained in the previous step; for each sensor,
  the interval is the last timestamp stored in table `a22.a22_traffic` + 1 to now
- store the traffic events in the table `a22.a22_traffic`
- sleep 30 seconds and repeat
 
As an implementation detail, the operation is performed per sensor group and not per
sensor as that is the web service API granularity.

***Follow mode is transactionally safe***, in the sense that it can be interrupted at
any given time for whatever reason and then safely restarted without manual intervention.
It is also designed to keep running if the web service or database becomes unavailable.
It will just sleep and reconnect.

However, follow mode is slow (not multi threaded, so the web service latency adds up)
and **not** memory efficient.

**For performance reasons, the lower bound of the interval to download is capped at 1 week in the past**. 

(:exclamation:) If you want to bring up a database that is outdated for more than
a week (and want to avoid gaps in the data), you need to first catch up using bulk mode.

As one week of backlog can already be a lot of data, it is recommended to catch up as close
as possible to the current time using bulk mode, for example to 00:00:00 of the current day
and only then start follow mode again.

Here is sample output of the application log in follow mode:

```
A22TrafficConnector invoked at 2019-06-23T15:16:55.941Z[UTC]
2019-06-23T15:16:56.300Z[UTC] A22TrafficConnector follow mode: woke up (iteration 1)
auth OK, new token = xxxxxxxx-xxxx-xxxx-xxxx-************, time = 2019-06-23T15:16:56.594Z[UTC]
follow mode: number of sensors: xxxx
follow mode: new sensor count: 0
follow mode: getting max(timestamp) for each sensor capped at 1560698217
........................................
follow mode: getting events:
th0 response codes: {500=xxx, 200=xxx}
follow mode: xxxxxxxx records (retrieve 380223 ms, store 200207 ms)
de-auth OK, old token = xxxxxxxx-xxxx-xxxx-xxxx-************, time = 2019-06-23T15:28:42.099Z[UTC]
2019-06-23T15:28:42.099Z[UTC] A22TrafficConnector follow mode: going to sleep
2019-06-23T15:29:12.099Z[UTC] A22TrafficConnector follow mode: woke up (iteration 2)
auth OK, new token = 3de44b05-5571-4054-998d-************, time = 2019-06-23T15:29:12.198Z[UTC]
follow mode: number of sensors: xxxx
follow mode: new sensor count: 0
follow mode: getting max(timestamp) for each sensor capped at 1560698952
........................................
follow mode: getting events:
th0 response codes: {500=xxx, 200=xxx}
follow mode: xxxxxxxx records (retrieve 35344 ms, store 4145 ms)
de-auth OK, old token = xxxxxxxx-xxxx-xxxx-xxxx-************, time = 2019-06-23T15:32:10.455Z[UTC]
2019-06-23T15:32:10.455Z[UTC] A22TrafficConnector follow mode: going to sleep
2019-06-23T15:32:40.455Z[UTC] A22TrafficConnector follow mode: woke up (iteration 3)
auth OK, new token = 47eb5ef4-afd1-4b96-ad4c-************, time = 2019-06-23T15:32:40.561Z[UTC]
follow mode: number of sensors: xxxx
follow mode: new sensor count: 0
follow mode: getting max(timestamp) for each sensor capped at 1560699160
........................................
follow mode: getting events:
th0 response codes: {500=xxx, 200=xxx}
follow mode: xxxxxxxx records (retrieve 27567 ms, store 1351 ms)
de-auth OK, old token = xxxxxxxx-xxxx-xxxx-xxxx-************, time = 2019-06-23T15:35:27.989Z[UTC]
2019-06-23T15:35:27.989Z[UTC] A22TrafficConnector follow mode: going to sleep
[...]
```

About "ghost" sensors.

Sometimes the A22 sends traffic events related to unknown sensors. Those
sensors would not be correctly taken into account when computing the last
timestamp stored for the sensor group to be polled. This might lead to
repeated downloads of lots of data for these unknown sensors.

To work around this problem, a "ghost" sensor table (`a22.a22_ghost_station`)
has been introduced, where unknown sensor identifiers are stored. This table is
used to correct the last timestamp computation and helps avoid the repeated
download problem.

The `a22.a22_ghost_station` can be safely ignored by users of the data. When
the A22 web service adds former "ghost" sensor to the official list, they get
automatically added to `a22.a22_station` as any new sensor.  No maintenance is
needed. 


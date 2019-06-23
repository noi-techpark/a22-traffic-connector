/*
    A22 traffic API connector.

    Retrieve A22 traffic data and store it into a PostgreSQL database.

    (C) 2018 IDM Suedtirol - Alto Adige
    (C) 2019 NOI Techpark Südtirol / Alto Adige

    Author: Chris Mair - chris@1006.org  
 */

CREATE SCHEMA a22;

-- the list of sensors
-- code has the form 'A22:coil_id:sensor_id'
CREATE TABLE a22.a22_station (
    code text primary key,
    name text NOT NULL,
    geo text NOT NULL
);

-- the web service credentials, the application will look for credentials
-- stored at id = 1
CREATE TABLE a22.a22_webservice (
    id serial primary key,
    url character varying NOT NULL,
    username character varying NOT NULL,
    password character varying,
    unique(url)
);

-- the traffic transit events
-- note there is intentionally no foreign key linking stationcode to a22.a22_station 

CREATE TABLE a22.a22_traffic (
    stationcode text,
    "timestamp" integer,
    distance double precision,
    headway double precision,
    length double precision,
    axles integer,
    against_traffic boolean,
    class integer,
    speed double precision,
    direction integer
);

CREATE INDEX a22_traffic_stationcode_ix ON a22.a22_traffic USING btree (stationcode);
CREATE INDEX a22_traffic_timestamp_ix ON a22.a22_traffic USING btree ("timestamp");

#!/bin/bash
# Create a22_webservice entry for local docker DB instance
psql <<-END
    insert into a22.a22_webservice (id, url, username, password) values (1, '${A22_URL}', '${A22_USERNAME}', '${A22_PASSWORD}')
END

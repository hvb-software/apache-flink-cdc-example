// Wait for MongoDB to be ready
var attempt = 0;
while(true) {
    try {
        db.adminCommand({ping: 1});
        break;
    } catch (e) {
        if (attempt++ >= 30) { // 30 attempts * 1s = 30s timeout
            print("Timeout waiting for MongoDB to start");
            quit(1);
        }
        sleep(1000);
    }
}

// Initialize replica set
rs.initiate({
    _id: "rs0",
    members: [{
        _id: 0,
        host: "localhost:27017",
        priority: 1
    }]
});

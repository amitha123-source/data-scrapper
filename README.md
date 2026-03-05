WorkFlow :
Java Service
    |
    |-- runRobot1()
    |
    |-- waitForCompletion()
    |
    |-- extractProductLinks()
    |
    |-- runRobot2Bulk()
    |
    |-- waitForCompletion()
    |
    |-- saveRobot2Data()   ✅ (IMPORTANT STEP)
    |
    |-- extractProductNames()
    |
    |-- runRobot3Bulk()
    |
    |-- waitForCompletion()
    |
    |-- saveRobot3Data()   ✅
    |
    |-- returnSuccess()

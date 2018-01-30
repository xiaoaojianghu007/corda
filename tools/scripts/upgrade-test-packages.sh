#!/bin/bash

find $1 -name "*.kt" -exec sed -i "
s/net.corda.testing.\*/net.corda.testing.core.\*/g
s/net.corda.testing.generateStateRef/net.corda.testing.core.generateStateRef/g
s/net.corda.testing.freeLocalHostAndPort/net.corda.testing.core.freeLocalHostAndPort/g
s/net.corda.testing.freePort/net.corda.testing.core.freePort/g
s/net.corda.testing.getFreeLocalPorts/net.corda.testing.core.getFreeLocalPorts/g
s/net.corda.testing.getTestPartyAndCertificate/net.corda.testing.core.getTestPartyAndCertificate/g
s/net.corda.testing.TestIdentity/net.corda.testing.core.TestIdentity/g
s/net.corda.testing.chooseIdentity/net.corda.testing.core.chooseIdentity/g
s/net.corda.testing.singleIdentity/net.corda.testing.core.singleIdentity/g
s/net.corda.testing.TEST_TX_TIME/net.corda.testing.core.TEST_TX_TIME/g
s/net.corda.testing.DUMMY_NOTARY_NAME/net.corda.testing.core.DUMMY_NOTARY_NAME/g
s/net.corda.testing.DUMMY_BANK_A_NAME/net.corda.testing.core.DUMMY_BANK_A_NAME/g
s/net.corda.testing.DUMMY_BANK_B_NAME/net.corda.testing.core.DUMMY_BANK_B_NAME/g
s/net.corda.testing.DUMMY_BANK_C_NAME/net.corda.testing.core.DUMMY_BANK_C_NAME/g
s/net.corda.testing.BOC_NAME/net.corda.testing.core.BOC_NAME/g
s/net.corda.testing.ALICE_NAME/net.corda.testing.core.ALICE_NAME/g
s/net.corda.testing.BOB_NAME/net.corda.testing.core.BOB_NAME/g
s/net.corda.testing.CHARLIE_NAME/net.corda.testing.core.CHARLIE_NAME/g
s/net.corda.testing.DEV_INTERMEDIATE_CA/net.corda.testing.core.DEV_INTERMEDIATE_CA/g
s/net.corda.testing.DEV_ROOT_CA/net.corda.testing.core.DEV_ROOT_CA/g
s/net.corda.testing.dummyCommand/net.corda.testing.core.dummyCommand/g
s/net.corda.testing.DummyCommandData/net.corda.testing.core.DummyCommandData/g
s/net.corda.testing.MAX_MESSAGE_SIZE/net.corda.testing.core.MAX_MESSAGE_SIZE/g
s/net.corda.testing.SerializationEnvironmentRule/net.corda.testing.core.SerializationEnvironmentRule/g
s/net.corda.testing.setGlobalSerialization/net.corda.testing.core.setGlobalSerialization/g
s/net.corda.testing.expect/net.corda.testing.core.expect/g
s/net.corda.testing.sequence/net.corda.testing.core.sequence/g
s/net.corda.testing.parallel/net.corda.testing.core.parallel/g
s/net.corda.testing.replicate/net.corda.testing.core.replicate/g
s/net.corda.testing.genericExpectEvents/net.corda.testing.core.genericExpectEvents/g
s/net.corda.testing.FlowStackSnapshotFactoryImpl/net.corda.testing.services.FlowStackSnapshotFactoryImpl/g
" '{}' \;
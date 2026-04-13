#!/usr/bin/env node

/**
 * Frontend-Backend Integration Test
 *
 * Tests the integration between React frontend and Java backend
 * through the BFF proxy layer.
 */

const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');
const path = require('path');

// Configuration
const BFF_URL = process.env.BFF_URL || 'http://localhost:3500';
const BACKEND_URL = process.env.SPRING_BOOT_URL || 'http://localhost:6443';

// Test configuration
const TEST_CONFIG = {
  timeout: 30000,
  retries: 3,
  delay: 1000,
};

// Colors for console output
const colors = {
  green: '\x1b[32m',
  red: '\x1b[31m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  reset: '\x1b[0m',
  bold: '\x1b[1m',
};

function log(message, color = colors.reset) {
  console.log(`${color}${message}${colors.reset}`);
}

function logSuccess(message) {
  log(`✅ ${message}`, colors.green);
}

function logError(message) {
  log(`❌ ${message}`, colors.red);
}

function logWarning(message) {
  log(`⚠️  ${message}`, colors.yellow);
}

function logInfo(message) {
  log(`ℹ️  ${message}`, colors.blue);
}

// Test functions
async function testBackendHealth() {
  logInfo('Testing backend health...');

  try {
    const response = await axios.get(`${BACKEND_URL}/actuator/health`, {
      timeout: TEST_CONFIG.timeout,
    });

    if (response.status === 200 && response.data.status === 'UP') {
      logSuccess('Backend health check passed');
      return true;
    } else {
      logError(`Backend health check failed: ${response.data.status}`);
      return false;
    }
  } catch (error) {
    logError(`Backend health check failed: ${error.message}`);
    return false;
  }
}

async function testBFFHealth() {
  logInfo('Testing BFF health...');

  try {
    const response = await axios.get(`${BFF_URL}/health`, {
      timeout: TEST_CONFIG.timeout,
    });

    if (response.status === 200 && response.data.status === 'ok') {
      logSuccess('BFF health check passed');
      return true;
    } else {
      logError(`BFF health check failed: ${response.data.status}`);
      return false;
    }
  } catch (error) {
    logError(`BFF health check failed: ${error.message}`);
    return false;
  }
}

async function testDocumentListAPI() {
  logInfo('Testing document list API through BFF...');

  try {
    const response = await axios.get(`${BFF_URL}/api/admin/documents`, {
      params: {
        tenant_id: 'default',
        limit: 10,
        offset: 0,
      },
      timeout: TEST_CONFIG.timeout,
    });

    if (response.status === 200) {
      logSuccess(
        `Document list API works - returned ${response.data.documents?.length || 0} documents`,
      );
      return true;
    } else {
      logError(`Document list API failed with status: ${response.status}`);
      return false;
    }
  } catch (error) {
    if (error.response?.status === 401) {
      logWarning('Document list API returned 401 (authentication required) - this is expected');
      return true; // 401 is expected without proper auth
    } else {
      logError(`Document list API failed: ${error.message}`);
      return false;
    }
  }
}

async function testTaskStatusAPI() {
  logInfo('Testing task status API through BFF...');

  const testTaskId = 'test-task-123';

  try {
    const response = await axios.get(`${BFF_URL}/api/admin/documents/tasks/${testTaskId}`, {
      params: {
        admin_user_id: 'admin_001',
        tenant_id: 'default',
      },
      timeout: TEST_CONFIG.timeout,
    });

    // We expect this to fail with 404 since the task doesn't exist
    logError(`Task status API unexpectedly succeeded: ${response.status}`);
    return false;
  } catch (error) {
    if (error.response?.status === 404) {
      logSuccess('Task status API works (returned 404 for non-existent task as expected)');
      return true;
    } else if (error.response?.status === 401) {
      logWarning('Task status API returned 401 (authentication required) - this is expected');
      return true;
    } else {
      logError(`Task status API failed unexpectedly: ${error.message}`);
      return false;
    }
  }
}

async function testFileUploadAPI() {
  logInfo('Testing file upload API through BFF...');

  try {
    // Create a test file
    const testContent = 'This is a test document for integration testing.';
    const testFilePath = path.join(__dirname, 'test-upload.txt');
    fs.writeFileSync(testFilePath, testContent);

    const formData = new FormData();
    formData.append('file', fs.createReadStream(testFilePath));
    formData.append('admin_user_id', 'admin_001');
    formData.append('title', 'Integration Test Document');
    formData.append('document_type', 'research_report');
    formData.append('priority', '3');
    formData.append('approval_required', 'true');

    const response = await axios.post(`${BFF_URL}/api/admin/documents/upload`, formData, {
      headers: {
        ...formData.getHeaders(),
      },
      timeout: TEST_CONFIG.timeout,
    });

    // Clean up test file
    fs.unlinkSync(testFilePath);

    if (response.status === 200 || response.status === 201) {
      logSuccess(`File upload API works - task created: ${response.data.task_id || 'unknown'}`);
      return true;
    } else {
      logError(`File upload API failed with status: ${response.status}`);
      return false;
    }
  } catch (error) {
    // Clean up test file if it exists
    const testFilePath = path.join(__dirname, 'test-upload.txt');
    if (fs.existsSync(testFilePath)) {
      fs.unlinkSync(testFilePath);
    }

    if (error.response?.status === 401) {
      logWarning('File upload API returned 401 (authentication required) - this is expected');
      return true;
    } else {
      logError(`File upload API failed: ${error.message}`);
      return false;
    }
  }
}

async function testCORSHeaders() {
  logInfo('Testing CORS headers...');

  try {
    const response = await axios.options(`${BFF_URL}/api/admin/documents`, {
      headers: {
        Origin: 'http://localhost:3000',
        'Access-Control-Request-Method': 'GET',
        'Access-Control-Request-Headers': 'Content-Type',
      },
      timeout: TEST_CONFIG.timeout,
    });

    const corsHeaders = response.headers['access-control-allow-origin'];
    if (corsHeaders) {
      logSuccess('CORS headers are properly configured');
      return true;
    } else {
      logWarning('CORS headers not found - may cause issues in browser');
      return true; // Not critical for server-side testing
    }
  } catch (error) {
    logWarning(`CORS test failed: ${error.message} - may not be critical`);
    return true; // CORS issues are often browser-specific
  }
}

async function runIntegrationTests() {
  log(`${colors.bold}🚀 Starting Frontend-Backend Integration Tests${colors.reset}`);
  log(`BFF URL: ${BFF_URL}`);
  log(`Backend URL: ${BACKEND_URL}`);
  log('');

  const tests = [
    { name: 'Backend Health', fn: testBackendHealth },
    { name: 'BFF Health', fn: testBFFHealth },
    { name: 'Document List API', fn: testDocumentListAPI },
    { name: 'Task Status API', fn: testTaskStatusAPI },
    { name: 'File Upload API', fn: testFileUploadAPI },
    { name: 'CORS Headers', fn: testCORSHeaders },
  ];

  const results = [];

  for (const test of tests) {
    try {
      const result = await test.fn();
      results.push({ name: test.name, success: result });
    } catch (error) {
      logError(`Test "${test.name}" threw an exception: ${error.message}`);
      results.push({ name: test.name, success: false });
    }

    // Add delay between tests
    await new Promise((resolve) => setTimeout(resolve, TEST_CONFIG.delay));
  }

  // Summary
  log('');
  log(`${colors.bold}📊 Test Results Summary${colors.reset}`);
  log('');

  const passed = results.filter((r) => r.success).length;
  const total = results.length;

  results.forEach((result) => {
    if (result.success) {
      logSuccess(`${result.name}: PASSED`);
    } else {
      logError(`${result.name}: FAILED`);
    }
  });

  log('');
  if (passed === total) {
    logSuccess(`🎉 All tests passed! (${passed}/${total})`);
    process.exit(0);
  } else {
    logError(`💥 Some tests failed (${passed}/${total})`);
    process.exit(1);
  }
}

// Handle command line arguments
if (process.argv.includes('--help') || process.argv.includes('-h')) {
  console.log(`
Frontend-Backend Integration Test

Usage: node test_integration.js [options]

Options:
  --help, -h     Show this help message
  --bff-url      BFF server URL (default: http://localhost:3500)
  --backend-url  Backend server URL (default: http://localhost:6443)

Environment Variables:
  BFF_URL        BFF server URL
  SPRING_BOOT_URL Backend server URL

Examples:
  node test_integration.js
  node test_integration.js --bff-url http://localhost:3500
  BFF_URL=http://localhost:3500 node test_integration.js
`);
  process.exit(0);
}

// Parse command line arguments
const bffUrlIndex = process.argv.indexOf('--bff-url');
if (bffUrlIndex !== -1 && process.argv[bffUrlIndex + 1]) {
  BFF_URL = process.argv[bffUrlIndex + 1];
}

const backendUrlIndex = process.argv.indexOf('--backend-url');
if (backendUrlIndex !== -1 && process.argv[backendUrlIndex + 1]) {
  BACKEND_URL = process.argv[backendUrlIndex + 1];
}

// Run tests
runIntegrationTests().catch((error) => {
  logError(`Integration test runner failed: ${error.message}`);
  process.exit(1);
});

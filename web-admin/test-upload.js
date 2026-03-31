#!/usr/bin/env node

/**
 * Test script for the engineering-grade file upload implementation
 * Tests the complete flow: Browser → BFF → Spring Boot
 */

import fs from 'fs';
import path from 'path';
import FormData from 'form-data';
import axios from 'axios';

// Configuration
const BFF_URL = process.env.BFF_URL || 'http://localhost:3500';
const TEST_FILE_PATH = process.env.TEST_FILE || './test-document.txt';

// Create a test file if it doesn't exist
function createTestFile() {
  if (!fs.existsSync(TEST_FILE_PATH)) {
    const testContent = `
# Test Document for Upload

This is a test document created for testing the engineering-grade file upload implementation.

## Features Tested
- File upload via BFF layer
- Stream forwarding to Spring Boot
- Metadata handling
- Progress tracking
- Error handling

## Test Details
- Created: ${new Date().toISOString()}
- File size: ${Buffer.byteLength('test content', 'utf8')} bytes
- Upload method: XHR with FormData

## Expected Behavior
1. File should be uploaded successfully
2. Progress should be tracked in real-time
3. Metadata should be preserved
4. Response should include task_id and document_id
5. No temporary files should be created in BFF layer

This test validates the complete upload pipeline.
    `.trim();
    
    fs.writeFileSync(TEST_FILE_PATH, testContent);
    console.log(`✅ Created test file: ${TEST_FILE_PATH}`);
  }
}

// Test the upload functionality
async function testUpload() {
  try {
    console.log('🚀 Starting file upload test...');
    
    // Create test file
    createTestFile();
    
    // Prepare form data
    const form = new FormData();
    form.append('file', fs.createReadStream(TEST_FILE_PATH));
    form.append('title', 'Test Document Upload');
    form.append('document_type', 'research_report');
    form.append('priority', '4');
    form.append('approval_required', 'true');
    form.append('admin_user_id', 'test_admin');
    form.append('admin_notes', 'Automated test upload via Node.js script');
    form.append('symbol', 'TEST');
    form.append('broker', 'Test Broker');
    
    // Upload the file
    console.log('📤 Uploading file to BFF...');
    const startTime = Date.now();
    
    const response = await axios.post(
      `${BFF_URL}/api/admin/documents/upload`,
      form,
      {
        headers: {
          ...form.getHeaders(),
          'User-Agent': 'Upload-Test-Script/1.0'
        },
        timeout: 60000, // 60 seconds timeout
        maxContentLength: Infinity,
        maxBodyLength: Infinity
      }
    );
    
    const duration = Date.now() - startTime;
    
    // Validate response
    if (response.status === 200 && response.data.success) {
      console.log('✅ Upload successful!');
      console.log(`⏱️  Duration: ${duration}ms`);
      console.log('📋 Response:', JSON.stringify(response.data, null, 2));
      
      // Validate response structure
      const { data } = response.data;
      if (data.task_id && data.document_id) {
        console.log('✅ Response structure is valid');
        console.log(`📄 Document ID: ${data.document_id}`);
        console.log(`🔄 Task ID: ${data.task_id}`);
        console.log(`📊 Priority: ${data.priority}`);
        console.log(`✋ Approval Required: ${data.approval_required}`);
      } else {
        console.log('❌ Response structure is invalid - missing required fields');
      }
    } else {
      console.log('❌ Upload failed');
      console.log('Response:', response.data);
    }
    
  } catch (error) {
    console.log('💥 Upload test failed');
    
    if (error.response) {
      console.log(`❌ HTTP ${error.response.status}: ${error.response.statusText}`);
      console.log('Response data:', error.response.data);
    } else if (error.request) {
      console.log('❌ Network error - no response received');
      console.log('Request details:', error.request);
    } else {
      console.log('❌ Error:', error.message);
    }
    
    console.log('\n🔍 Troubleshooting:');
    console.log('1. Ensure BFF server is running on', BFF_URL);
    console.log('2. Ensure Spring Boot backend is running');
    console.log('3. Check network connectivity');
    console.log('4. Verify file permissions');
  }
}

// Test health endpoints
async function testHealth() {
  try {
    console.log('🏥 Testing health endpoints...');
    
    // Test BFF health
    const bffHealth = await axios.get(`${BFF_URL}/health`, { timeout: 5000 });
    console.log('✅ BFF health check passed');
    
    // Test upload service health
    const uploadHealth = await axios.get(`${BFF_URL}/api/upload/health`, { timeout: 5000 });
    console.log('✅ Upload service health check passed');
    
  } catch (error) {
    console.log('❌ Health check failed:', error.message);
    console.log('💡 Make sure the BFF server is running with: pnpm dev:full');
  }
}

// Main execution
async function main() {
  console.log('🧪 Engineering-Grade File Upload Test Suite');
  console.log('=' .repeat(50));
  
  // Test health first
  await testHealth();
  console.log('');
  
  // Test upload
  await testUpload();
  
  console.log('');
  console.log('🏁 Test completed');
}

// Run the test
main().catch(console.error);

export { testUpload, testHealth, createTestFile };
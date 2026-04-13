#!/usr/bin/env node

/**
 * 测试增强的BFF代理日志功能
 *
 * 这个脚本会发送各种类型的请求来测试不同的日志场景
 */

const axios = require('axios');

const BFF_BASE_URL = 'http://localhost:3001';
const BACKEND_BASE_URL = 'http://localhost:6443';

async function testEnhancedLogging() {
  console.log('🧪 Testing Enhanced BFF Proxy Logging...\n');

  // 测试场景1: 成功请求
  console.log('📋 Test 1: Successful Request');
  try {
    const response = await axios.get(`${BFF_BASE_URL}/api/actuator/health`, {
      timeout: 5000,
    });
    console.log(`✅ Success: ${response.status} ${response.statusText}`);
  } catch (error) {
    console.log(`❌ Error: ${error.message}`);
  }

  await sleep(1000);

  // 测试场景2: 401 认证错误
  console.log('\n📋 Test 2: Authentication Error (401)');
  try {
    const response = await axios.get(`${BFF_BASE_URL}/api/admin/documents`, {
      timeout: 5000,
    });
    console.log(`✅ Success: ${response.status} ${response.statusText}`);
  } catch (error) {
    if (error.response) {
      console.log(`❌ Expected 401 Error: ${error.response.status} ${error.response.statusText}`);
    } else {
      console.log(`❌ Network Error: ${error.message}`);
    }
  }

  await sleep(1000);

  // 测试场景3: 404 Not Found
  console.log('\n📋 Test 3: Not Found Error (404)');
  try {
    const response = await axios.get(`${BFF_BASE_URL}/api/nonexistent/endpoint`, {
      timeout: 5000,
    });
    console.log(`✅ Success: ${response.status} ${response.statusText}`);
  } catch (error) {
    if (error.response) {
      console.log(`❌ Expected 404 Error: ${error.response.status} ${error.response.statusText}`);
    } else {
      console.log(`❌ Network Error: ${error.message}`);
    }
  }

  await sleep(1000);

  // 测试场景4: 带认证的POST请求
  console.log('\n📋 Test 4: POST Request with Authentication');
  try {
    const response = await axios.post(
      `${BFF_BASE_URL}/api/admin/documents/upload`,
      {
        documentType: 'test',
        createdBy: 'test-user',
      },
      {
        headers: {
          'Content-Type': 'application/json',
          Cookie: 'session=test-session-cookie',
        },
        timeout: 5000,
      },
    );
    console.log(`✅ Success: ${response.status} ${response.statusText}`);
  } catch (error) {
    if (error.response) {
      console.log(`❌ Expected Error: ${error.response.status} ${error.response.statusText}`);
    } else {
      console.log(`❌ Network Error: ${error.message}`);
    }
  }

  await sleep(1000);

  // 测试场景5: 连接拒绝错误（如果后端未运行）
  console.log('\n📋 Test 5: Connection Refused (Backend Down)');
  try {
    // 尝试连接到一个不存在的端口
    const response = await axios.get(`http://localhost:9999/api/health`, {
      timeout: 2000,
    });
    console.log(`✅ Success: ${response.status} ${response.statusText}`);
  } catch (error) {
    if (error.code === 'ECONNREFUSED') {
      console.log(`❌ Expected Connection Refused: ${error.message}`);
    } else {
      console.log(`❌ Other Error: ${error.message}`);
    }
  }

  console.log('\n🎉 Enhanced logging test completed!');
  console.log('\n📝 Check the BFF server logs to see the enhanced logging output.');
  console.log('   Look for emojis and structured log data in the console.');
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// 运行测试
testEnhancedLogging().catch(console.error);

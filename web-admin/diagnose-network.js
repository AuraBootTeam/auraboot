#!/usr/bin/env node

/**
 * 网络连接诊断脚本
 * 用于诊断 ERR_ALPN_NEGOTIATION_FAILED 和其他网络问题
 */

import { spawn } from 'child_process';
import { createServer } from 'http';

const PORTS_TO_CHECK = [
  { port: 5173, service: 'Vite Dev Server' },
  { port: 3500, service: 'BFF Server' },
  { port: 6443, service: 'Spring Boot Backend' },
  { port: 8001, service: 'Python AI Service' },
];

const ENDPOINTS_TO_TEST = [
  { url: 'http://localhost:5173', name: 'Vite Dev Server' },
  { url: 'http://localhost:3500/health', name: 'BFF Health Check' },
  { url: 'http://localhost:6443/actuator/health', name: 'Spring Boot Health' },
  { url: 'http://localhost:8001/health', name: 'Python AI Health' },
];

console.log('🔍 AuraBoot Network Diagnostics\n');

// 检查端口占用
async function checkPorts() {
  console.log('📡 Checking port availability...');

  for (const { port, service } of PORTS_TO_CHECK) {
    try {
      const server = createServer();
      await new Promise((resolve, reject) => {
        server.listen(port, (err) => {
          if (err) reject(err);
          else resolve();
        });
      });

      server.close();
      console.log(`✅ Port ${port} (${service}): Available`);
    } catch (error) {
      console.log(`❌ Port ${port} (${service}): In use`);
    }
  }
  console.log();
}

// 测试HTTP连接
async function testEndpoints() {
  console.log('🌐 Testing HTTP endpoints...');

  for (const { url, name } of ENDPOINTS_TO_TEST) {
    try {
      const response = await fetch(url, {
        method: 'GET',
        timeout: 5000,
        headers: {
          'User-Agent': 'AuraBoot-Diagnostics/1.0',
        },
      });

      console.log(`✅ ${name}: ${response.status} ${response.statusText}`);
    } catch (error) {
      if (error.code === 'ECONNREFUSED') {
        console.log(`❌ ${name}: Connection refused (service not running)`);
      } else if (error.name === 'TimeoutError') {
        console.log(`⏰ ${name}: Timeout (service may be slow)`);
      } else {
        console.log(`❌ ${name}: ${error.message}`);
      }
    }
  }
  console.log();
}

// 检查系统信息
async function checkSystemInfo() {
  console.log('💻 System Information:');
  console.log(`   Node.js: ${process.version}`);
  console.log(`   Platform: ${process.platform}`);
  console.log(`   Architecture: ${process.arch}`);
  console.log(`   Memory: ${Math.round(process.memoryUsage().heapUsed / 1024 / 1024)}MB used`);
  console.log();
}

// 检查网络配置
async function checkNetworkConfig() {
  console.log('🔧 Network Configuration:');

  // 检查环境变量
  const envVars = ['BFF_PORT', 'SPRING_BOOT_URL', 'NODE_ENV', 'LOG_LEVEL', 'BFF_VERBOSE_LOGGING'];

  envVars.forEach((varName) => {
    const value = process.env[varName];
    console.log(`   ${varName}: ${value || 'not set'}`);
  });
  console.log();
}

// 测试文件上传
async function testFileUpload() {
  console.log('📁 Testing file upload permission...');

  try {
    // 创建一个小的测试文件
    const testData = new Blob(['Hello, AuraBoot!'], { type: 'text/plain' });
    const formData = new FormData();
    formData.append('file', testData, 'test.txt');
    formData.append('documentType', 'test');
    formData.append('createdBy', 'diagnostics');

    const response = await fetch('http://localhost:3500/api/admin/documents/upload', {
      method: 'POST',
      body: formData,
      credentials: 'include',
    });

    if (response.ok) {
      console.log('✅ File upload endpoint: Accessible');
    } else {
      console.log(`❌ File upload endpoint: ${response.status} ${response.statusText}`);
    }
  } catch (error) {
    console.log(`❌ File upload endpoint: ${error.message}`);
  }
  console.log();
}

// 提供解决建议
function provideSuggestions() {
  console.log('💡 Troubleshooting Suggestions:');
  console.log();

  console.log('🔧 For ERR_ALPN_NEGOTIATION_FAILED:');
  console.log('   1. Ensure BFF server is running on port 3500');
  console.log('   2. Check Vite proxy configuration points to correct BFF port');
  console.log('   3. Disable HTTP/2 if causing issues');
  console.log('   4. Clear browser cache and restart dev servers');
  console.log();

  console.log('🚀 To start services:');
  console.log('   1. Backend: cd platform && ./gradlew bootRun');
  console.log('   2. Python AI: cd chat && poetry run python app/main.py');
  console.log('   3. Frontend: cd web-admin && ./start-dev.sh');
  console.log();

  console.log('🔍 For debugging:');
  console.log('   1. Check browser developer tools Network tab');
  console.log('   2. Enable verbose logging: export BFF_VERBOSE_LOGGING=true');
  console.log('   3. Check BFF server logs for detailed error messages');
  console.log('   4. Verify all services are healthy before testing uploads');
}

// 主函数
async function main() {
  try {
    await checkSystemInfo();
    await checkNetworkConfig();
    await checkPorts();
    await testEndpoints();
    await testFileUpload();
    provideSuggestions();

    console.log('✅ Diagnostics completed!');
  } catch (error) {
    console.error('❌ Diagnostics failed:', error.message);
    process.exit(1);
  }
}

// 运行诊断
main().catch(console.error);

#!/bin/bash

# Simple cURL test for file upload
echo "🧪 Testing file upload with cURL..."

# Create a simple test file
echo "This is a test file for upload testing." > test-simple.txt

# Test the upload
echo "📤 Uploading file via cURL..."
curl -X POST http://localhost:3500/api/admin/documents/upload \
  -F "file=@test-simple.txt" \
  -F "title=Simple Test Upload" \
  -F "document_type=research_report" \
  -F "priority=3" \
  -F "admin_user_id=test_admin" \
  -H "User-Agent: cURL-Test/1.0" \
  -v

echo ""
echo "🏁 cURL test completed"
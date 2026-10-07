import { createServer } from 'node:http'

const port = Number(process.env.PORT || 18082)

createServer((request, response) => {
  const roadside = request.url?.toLowerCase().includes('54a507c4-c038-41b5-bf60-bbecb9d052c6')
  const rows = roadside
    ? [{ id: 'S3-023-UNMAPPED-SPOT', parkingstatus: '2' }]
    : [{ ID: 'S3-023-UNMAPPED-LOT', AVAILABLECAR: '1' }]

  response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
  response.end(JSON.stringify(rows))
}).listen(port, '0.0.0.0', () => {
  process.stdout.write(`NTPC unmapped test stub listening on ${port}\n`)
})

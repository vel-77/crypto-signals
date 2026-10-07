import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { TimeZoneProvider } from './timezone'
import './styles.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <TimeZoneProvider>
      <App />
    </TimeZoneProvider>
  </StrictMode>,
)

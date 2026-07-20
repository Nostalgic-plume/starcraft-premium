import axios from 'axios'
import { ElMessage } from 'element-plus'
import { createRequest } from '@shared/js/createRequest.js'
import router from '../router'

export default createRequest({ tokenKey: 'admin-token', router, axios, ElMessage })
